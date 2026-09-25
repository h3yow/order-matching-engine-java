package com.github.h3yow.ordermatchingenginejava.engine;

import com.github.h3yow.ordermatchingenginejava.account.Account;
import com.github.h3yow.ordermatchingenginejava.account.AccountService;
import com.github.h3yow.ordermatchingenginejava.domain.Order;
import com.github.h3yow.ordermatchingenginejava.domain.OrderStatus;
import com.github.h3yow.ordermatchingenginejava.domain.OrderType;
import com.github.h3yow.ordermatchingenginejava.domain.RejectReason;
import com.github.h3yow.ordermatchingenginejava.domain.Side;
import com.github.h3yow.ordermatchingenginejava.domain.Trade;
import com.github.h3yow.ordermatchingenginejava.persistence.Journal;
import com.github.h3yow.ordermatchingenginejava.risk.RiskChecker;
import com.github.h3yow.ordermatchingenginejava.risk.RiskLimits;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 撮合引擎。所有状态变更在引擎内置锁下串行执行，
 * 保证并发下单/撤单时校验与扣减原子完成，状态守恒。
 *
 * 幂等语义：
 *  - clientOrderId 去重：重复提交直接拒绝，不产生重复成交/扣款；
 *  - tradeId 去重：同一成交事件重复投递/重放不重复结算。
 */
@Service
public class MatchingEngine {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngine.class);

    private final Object lock = new Object();
    private final AccountService accountService;
    private final RiskChecker riskChecker;
    private final RiskLimits limits;
    private final Journal journal;

    private final OrderBook book = new OrderBook();
    private final Map<String, Order> ordersById = new HashMap<>();
    private final Map<String, String> orderIdByClientId = new HashMap<>();
    private final Set<String> appliedTradeIds = new HashSet<>();

    private long orderSeq = 0;
    private long tradeSeq = 0;
    private long lastTradePrice;

    public MatchingEngine(AccountService accountService, RiskChecker riskChecker,
                          RiskLimits limits, Journal journal) {
        this.accountService = accountService;
        this.riskChecker = riskChecker;
        this.limits = limits;
        this.journal = journal;
        this.lastTradePrice = limits.getReferencePrice();
    }

    /** 提交订单（幂等：clientOrderId 去重）。 */
    public OrderResult submitOrder(String clientOrderId, String accountId, Side side,
                                   OrderType type, Long price, long quantity) {
        synchronized (lock) {
            if (orderIdByClientId.containsKey(clientOrderId)) {
                log.info("[REJECT] clientOrderId={} reason={} 判定依据: 该客户端订单号已受理过",
                        clientOrderId, RejectReason.DUPLICATE_CLIENT_ORDER_ID);
                return OrderResult.rejected(RejectReason.DUPLICATE_CLIENT_ORDER_ID);
            }

            Order order = new Order(nextOrderId(), clientOrderId, accountId, side, type,
                    type == OrderType.MARKET ? null : price, quantity);
            order.setSequence(orderSeq);
            Account account = accountService.getOrCreate(accountId);

            RejectReason reject = riskChecker.check(order, account.getCashAvailable(),
                    account.getPositionAvailable(), lastTradePrice);
            if (reject != null) {
                order.markRejected();
                ordersById.put(order.getOrderId(), order);
                orderIdByClientId.put(clientOrderId, order.getOrderId());
                log.info("[REJECT] {} reason={} 判定依据: 风控校验未通过 {}",
                        order.getOrderId(), reject, account.snapshot());
                return OrderResult.rejected(reject);
            }

            // 校验通过后原子冻结
            if (side == Side.BUY) {
                account.freezeCash(riskChecker.requiredCash(order, lastTradePrice));
            } else {
                account.freezePosition(quantity);
            }
            ordersById.put(order.getOrderId(), order);
            orderIdByClientId.put(clientOrderId, order.getOrderId());
            journal.appendOrderAccepted(order);
            log.info("[ACCEPT] {} {}", order, account.snapshot());

            // 撮合
            Long cap = (type == OrderType.MARKET && side == Side.BUY)
                    ? marketBuyEstimate() : null;
            List<OrderBook.Match> matches = book.match(order, cap);
            List<Trade> trades = new ArrayList<>();
            for (OrderBook.Match m : matches) {
                Trade trade = new Trade(nextTradeId(),
                        side == Side.BUY ? order.getOrderId() : m.maker().getOrderId(),
                        side == Side.BUY ? m.maker().getOrderId() : order.getOrderId(),
                        side == Side.BUY ? accountId : m.maker().getAccountId(),
                        side == Side.BUY ? m.maker().getAccountId() : accountId,
                        m.price(), m.quantity(), tradeSeq);
                settle(trade);
                appliedTradeIds.add(trade.getTradeId());
                journal.appendTrade(trade);
                lastTradePrice = m.price();
                trades.add(trade);
                if (m.maker().getRemainingQuantity() == 0) {
                    book.remove(m.maker());
                }
                log.info("[TRADE] {}", trade);
            }

            // 剩余数量处理：限价单挂簿，市价单剩余即撤
            if (order.getRemainingQuantity() > 0) {
                if (type == OrderType.LIMIT) {
                    book.add(order);
                } else {
                    unfreezeRemaining(order);
                    order.markCanceled();
                    journal.appendCanceled(order.getOrderId());
                    log.info("[CANCEL-RESIDUAL] {} 市价单剩余未成交即撤 {}", order.getOrderId(),
                            account.snapshot());
                }
            }
            return OrderResult.accepted(order.getOrderId(), trades);
        }
    }

    /** 撤销订单剩余数量。 */
    public OrderResult cancelOrder(String orderId) {
        synchronized (lock) {
            Order order = ordersById.get(orderId);
            if (order == null) {
                log.info("[REJECT-CANCEL] orderId={} reason={}", orderId, RejectReason.ORDER_NOT_FOUND);
                return OrderResult.rejected(RejectReason.ORDER_NOT_FOUND);
            }
            switch (order.getStatus()) {
                case FILLED -> {
                    log.info("[REJECT-CANCEL] {} reason={}", orderId, RejectReason.ORDER_ALREADY_FILLED);
                    return OrderResult.rejected(RejectReason.ORDER_ALREADY_FILLED);
                }
                case CANCELED -> {
                    log.info("[REJECT-CANCEL] {} reason={}", orderId, RejectReason.ORDER_ALREADY_CANCELED);
                    return OrderResult.rejected(RejectReason.ORDER_ALREADY_CANCELED);
                }
                case REJECTED -> {
                    log.info("[REJECT-CANCEL] {} reason={}", orderId, RejectReason.ORDER_ALREADY_REJECTED);
                    return OrderResult.rejected(RejectReason.ORDER_ALREADY_REJECTED);
                }
                default -> { }
            }
            book.remove(order);
            unfreezeRemaining(order);
            order.markCanceled();
            journal.appendCanceled(orderId);
            Account account = accountService.get(order.getAccountId());
            log.info("[CANCEL] {} remaining={} {}", orderId, order.getRemainingQuantity(),
                    account.snapshot());
            return OrderResult.accepted(orderId, List.of());
        }
    }

    /**
     * 应用一笔成交（重放/重复投递安全：按 tradeId 去重）。
     * 重复投递同一 tradeId 直接忽略，不重复结算。
     */
    public void applyTrade(Trade trade) {
        synchronized (lock) {
            if (!appliedTradeIds.add(trade.getTradeId())) {
                log.info("[DUP-TRADE] tradeId={} 重复投递，跳过结算", trade.getTradeId());
                return;
            }
            Order buy = ordersById.get(trade.getBuyOrderId());
            Order sell = ordersById.get(trade.getSellOrderId());
            if (buy != null) {
                buy.addFill(trade.getQuantity());
            }
            if (sell != null) {
                sell.addFill(trade.getQuantity());
            }
            settle(trade);
            lastTradePrice = trade.getPrice();
            log.info("[APPLY-TRADE] {}", trade);
        }
    }

    /** 启动时依据流水重放，恢复到与重启前一致的可见状态。 */
    public void replay() {
        synchronized (lock) {
            for (String line : journal.readAll()) {
                if (line.isBlank()) {
                    continue;
                }
                String[] f = line.split("\\|");
                switch (f[0]) {
                    case "O" -> {
                        if (ordersById.containsKey(f[1])) {
                            // 重复重放/重复投递：订单已存在，跳过避免重复冻结
                            continue;
                        }
                        Order order = new Order(f[1], f[2], f[3], Side.valueOf(f[4]),
                                OrderType.valueOf(f[5]),
                                f[6].isEmpty() ? null : Long.parseLong(f[6]),
                                Long.parseLong(f[7]));
                        order.setSequence(Long.parseLong(f[8]));
                        orderSeq = Math.max(orderSeq, order.getSequence());
                        ordersById.put(order.getOrderId(), order);
                        orderIdByClientId.put(order.getClientOrderId(), order.getOrderId());
                        Account account = accountService.getOrCreate(order.getAccountId());
                        if (order.getSide() == Side.BUY) {
                            account.freezeCash(frozenUnitPrice(order) * order.getQuantity());
                        } else {
                            account.freezePosition(order.getQuantity());
                        }
                    }
                    case "T" -> {
                        Trade trade = new Trade(f[1], f[2], f[3], f[4], f[5],
                                Long.parseLong(f[6]), Long.parseLong(f[7]),
                                Long.parseLong(f[8]));
                        tradeSeq = Math.max(tradeSeq, trade.getSequence());
                        applyTrade(trade);
                    }
                    case "C" -> {
                        Order order = ordersById.get(f[1]);
                        if (order != null && order.isActive()) {
                            unfreezeRemaining(order);
                            order.markCanceled();
                        }
                    }
                    default -> log.warn("未知流水行: {}", line);
                }
            }
            // 重建订单簿：仅限价且仍有剩余的活动单
            for (Order order : ordersById.values()) {
                if (order.isActive() && order.getType() == OrderType.LIMIT
                        && order.getRemainingQuantity() > 0) {
                    book.add(order);
                }
            }
            log.info("[REPLAY] 完成: orders={} trades={} lastPrice={}",
                    ordersById.size(), appliedTradeIds.size(), lastTradePrice);
        }
    }

    public Order getOrder(String orderId) {
        synchronized (lock) {
            return ordersById.get(orderId);
        }
    }

    public long getLastTradePrice() {
        synchronized (lock) {
            return lastTradePrice;
        }
    }

    // ---- 内部方法（须在锁内调用） ----

    /** 成交结算：买方冻结资金按实际价扣减、差额退回，卖方冻结持仓转为资金。 */
    private void settle(Trade trade) {
        Account buyer = accountService.getOrCreate(trade.getBuyAccountId());
        Account seller = accountService.getOrCreate(trade.getSellAccountId());
        Order buyOrder = ordersById.get(trade.getBuyOrderId());
        long unitFrozen = buyOrder != null ? frozenUnitPrice(buyOrder) : trade.getPrice();
        long frozenToConsume = unitFrozen * trade.getQuantity();
        buyer.settleBuy(frozenToConsume, trade.getPrice() * trade.getQuantity(),
                trade.getQuantity());
        seller.settleSell(trade.getQuantity(), trade.getPrice() * trade.getQuantity());
    }

    /** 解冻订单剩余数量对应的资金/持仓。 */
    private void unfreezeRemaining(Order order) {
        Account account = accountService.get(order.getAccountId());
        if (order.getSide() == Side.BUY) {
            account.unfreezeCash(frozenUnitPrice(order) * order.getRemainingQuantity());
        } else {
            account.unfreezePosition(order.getRemainingQuantity());
        }
    }

    /** 买入每单位冻结金额：限价单按限价，市价单按风控估算价。 */
    private long frozenUnitPrice(Order order) {
        return order.getType() == OrderType.LIMIT ? order.getPrice() : marketBuyEstimate();
    }

    private long marketBuyEstimate() {
        return lastTradePrice * (10_000 + limits.getMaxPriceDeviationBps()) / 10_000;
    }

    private String nextOrderId() {
        return "O" + (++orderSeq);
    }

    private String nextTradeId() {
        return "T" + (++tradeSeq);
    }
}
