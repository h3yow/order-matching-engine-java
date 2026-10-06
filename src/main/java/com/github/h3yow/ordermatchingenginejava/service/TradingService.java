package com.github.h3yow.ordermatchingenginejava.service;

import com.github.h3yow.ordermatchingenginejava.config.TradingProperties;
import com.github.h3yow.ordermatchingenginejava.domain.Account;
import com.github.h3yow.ordermatchingenginejava.domain.Fill;
import com.github.h3yow.ordermatchingenginejava.domain.Order;
import com.github.h3yow.ordermatchingenginejava.domain.OrderStatus;
import com.github.h3yow.ordermatchingenginejava.domain.OrderType;
import com.github.h3yow.ordermatchingenginejava.domain.Position;
import com.github.h3yow.ordermatchingenginejava.domain.RejectReason;
import com.github.h3yow.ordermatchingenginejava.domain.Side;
import com.github.h3yow.ordermatchingenginejava.domain.TradingException;
import com.github.h3yow.ordermatchingenginejava.engine.Match;
import com.github.h3yow.ordermatchingenginejava.engine.MatchingEngine;
import com.github.h3yow.ordermatchingenginejava.engine.OrderBook;
import com.github.h3yow.ordermatchingenginejava.risk.RiskValidator;
import com.github.h3yow.ordermatchingenginejava.store.Amend;
import com.github.h3yow.ordermatchingenginejava.store.Event;
import com.github.h3yow.ordermatchingenginejava.store.EventStore;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 交易门面（单 JVM 单实例）。
 *
 * <p>所有写操作都在 {@code lock} 内串行执行，保证“风控校验 → 资金/持仓冻结 → 落单持久化 →
 * 撮合 → 结算”的原子性，并发下单不会突破限额或产生负余额。</p>
 *
 * <p>持久化与重放：先写事件流水再改内存状态；启动时严格按流水顺序重放
 * ORDER_ACCEPTED / FILL / ORDER_CANCELED，恢复出的订单簿、账户与重启前可见状态一致。
 * 成交以 fillId+ownerAccountId 幂等：重复投递/重复重放同一成交事件绝不重复结算。</p>
 */
@Service
public class TradingService {

    private static final Logger log = LoggerFactory.getLogger(TradingService.class);

    private final TradingProperties properties;
    private final EventStore eventStore;
    private final RiskValidator riskValidator;
    private final MatchingEngine matchingEngine;

    /** 全部写操作串行化。 */
    private final Object lock = new Object();

    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final Map<String, OrderBook> books = new HashMap<>();
    private final Map<Long, Order> ordersById = new HashMap<>();
    /** key = accountId + '|' + clientOrderId */
    private final Map<String, Order> clientIndex = new HashMap<>();
    /** 已结算成交：fillId + '|' + ownerAccountId，跨重放与外部投递共用。 */
    private final Set<String> settledFillKeys = new HashSet<>();
    /** 已生效改单：orderId + '|' + clientAmendId，重放时由 ORDER_AMENDED 流水重建。 */
    private final Set<String> amendedKeys = new HashSet<>();

    private long orderIdSeq = 0;
    private long seqCounter = 0;
    private long fillIdSeq = 0;
    private long matchIdSeq = 0;

    /** 各标的最近成交价，作为价格合理性与市价保护参考价。 */
    private final Map<String, Long> lastPrice = new HashMap<>();
    /** 尚无成交时外部注入的参考价。 */
    private final Map<String, Long> referenceHints = new HashMap<>();

    public TradingService(TradingProperties properties,
                          EventStore eventStore,
                          RiskValidator riskValidator,
                          MatchingEngine matchingEngine) {
        this.properties = properties;
        this.eventStore = eventStore;
        this.riskValidator = riskValidator;
        this.matchingEngine = matchingEngine;
    }

    @PostConstruct
    public void init() {
        synchronized (lock) {
            seedAccounts();
            replay();
            log.info("trading engine ready: accounts={}, orders={}, settledFillLegs={}",
                    accounts.size(), ordersById.size(), settledFillKeys.size());
        }
    }

    private void seedAccounts() {
        properties.getSeedAccounts().forEach((id, cash) ->
                accounts.put(id, new Account(id, cash)));
        properties.getSeedPositions().forEach((key, qty) -> {
            int idx = key.indexOf("__");
            if (idx <= 0) {
                throw new IllegalArgumentException("bad seed position key (expected account__symbol): " + key);
            }
            Account acc = accounts.computeIfAbsent(key.substring(0, idx), a -> new Account(a, 0L));
            Position p = acc.positionOf(key.substring(idx + 2));
            p.setQuantity(qty);
            p.setAvailable(qty);
        });
    }

    // ------------------------------------------------------------------
    // 重放
    // ------------------------------------------------------------------

    private void replay() {
        for (Event event : eventStore.readAll()) {
            switch (event.type()) {
                case ORDER_ACCEPTED -> replayOrder(event.order());
                case FILL -> replayFill(event.fill());
                case ORDER_CANCELED -> replayCancel(event.order());
                case ORDER_AMENDED -> replayAmend(event.amend());
            }
        }
        // 所有 FILL/CANCEL/AMEND 应用完毕后，仍处于 NEW 且有剩余的限价单才是重启前簿内可见挂单。
        // 按接收序号 seq 升序入簿：实时路径下同价档位 FIFO 顺序即 seq 顺序（改单换新 seq 排队尾），
        // 由此恢复出的各档位排队顺序与重启前逐字段一致。
        // 账户最终状态 = 种子 + FILL；在此之上补建这些活单仍占用的冻结，得到重启前的可用现金/持仓。
        List<Order> liveOrders = ordersById.values().stream()
                .filter(o -> o.getStatus() == OrderStatus.NEW
                        && o.getType() == OrderType.LIMIT && o.remainingQty() > 0)
                .sorted(Comparator.comparingLong(Order::getSeq))
                .toList();
        for (Order o : liveOrders) {
            book(o.getSymbol()).add(o);
            replayReserveLiveOrder(o);
        }
        assertInvariants("after replay");
    }

    /** 重放改单：推进价格/数量/接收序号；filledQty 与状态始终由 FILL/CANCEL 流水推进。 */
    private void replayAmend(Amend a) {
        Order o = ordersById.get(a.orderId());
        if (o == null) {
            log.warn("amend event references unknown order, skipped: orderId={}", a.orderId());
            return;
        }
        o.setLimitPrice(a.limitPrice());
        o.setQuantity(a.quantity());
        o.setSeq(a.seq());
        seqCounter = Math.max(seqCounter, a.seq());
        amendedKeys.add(amendKey(a.orderId(), a.clientAmendId()));
    }

    /** 重放补冻结：买单按剩余数量*限价占用可用现金；卖单占用可卖持仓。 */
    private void replayReserveLiveOrder(Order o) {
        Account acc = accounts.get(o.getAccountId());
        if (acc == null) {
            return;
        }
        long remaining = o.remainingQty();
        if (o.getSide() == Side.BUY) {
            acc.setAvailableCash(acc.getAvailableCash()
                    - remaining * o.getLimitPrice() / RiskValidator.SCALE);
        } else {
            Position pos = acc.positionOf(o.getSymbol());
            pos.setAvailable(pos.getAvailable() - remaining);
        }
    }

    private void replayOrder(Order o) {
        ordersById.put(o.getOrderId(), o);
        clientIndex.put(clientKey(o.getAccountId(), o.getClientOrderId()), o);
        orderIdSeq = Math.max(orderIdSeq, o.getOrderId());
        seqCounter = Math.max(seqCounter, o.getSeq());
    }

    private void replayFill(Fill f) {
        if (!settledFillKeys.add(fillKey(f.fillId(), f.ownerAccountId()))) {
            log.warn("duplicate fill leg skipped on replay: fillId={}, owner={}",
                    f.fillId(), f.ownerAccountId());
            return;
        }
        // 重放时订单来自“接受时”快照（filledQty=0），需要依据流水推进订单状态。
        advanceOrder(f.ownerOrderId(), f.quantity());
        settleReplay(f);
        fillIdSeq = Math.max(fillIdSeq, f.fillId());
        matchIdSeq = Math.max(matchIdSeq, f.matchId());
        lastPrice.put(f.symbol(), f.price());
    }

    private void advanceOrder(long orderId, long qty) {
        Order o = ordersById.get(orderId);
        if (o == null) {
            // 外部投递的 fill 可能引用尚未加载的订单（本实现不会发生），忽略订单推进，
            // 账户结算仍执行。
            return;
        }
        if (o.getStatus() == OrderStatus.NEW) {
            o.setFilledQty(o.getFilledQty() + qty);
            if (o.remainingQty() == 0) {
                o.setStatus(OrderStatus.FILLED);
            }
        }
    }

    private void replayCancel(Order canceledEvent) {
        Order current = ordersById.get(canceledEvent.getOrderId());
        if (current == null || current.getStatus() != OrderStatus.NEW) {
            return;
        }
        // 重放口径下没有“下单冻结”，账户最终状态 = 种子 + 全部 FILL；
        // 撤单只需推进订单状态，订单簿重建时据此跳过该订单。
        current.setStatus(OrderStatus.CANCELED);
    }

    // ------------------------------------------------------------------
    // 下单
    // ------------------------------------------------------------------

    public OrderResult submitOrder(String clientOrderId, String accountId, String symbol,
                                   String sideText, String typeText, long price, long quantity) {
        synchronized (lock) {
            Side side = parseSide(sideText);
            OrderType type = parseType(typeText);
            if (clientOrderId == null || clientOrderId.isBlank()) {
                throw new IllegalArgumentException("clientOrderId is required");
            }
            if (symbol == null || symbol.isBlank()) {
                throw new IllegalArgumentException("symbol is required");
            }

            // 幂等：同一账户同一 clientOrderId 重复提交，返回首次结果，不产生新成交/扣款。
            Order existing = clientIndex.get(clientKey(accountId, clientOrderId));
            if (existing != null) {
                log.info("idempotent submit hit: clientOrderId={}, account={}, orderId={}, status={}, filledQty={}, snapshot={}",
                        clientOrderId, accountId, existing.getOrderId(),
                        existing.getStatus(), existing.getFilledQty(), snapshot(accountId));
                return new OrderResult(existing, true,
                        existing.getStatus() == OrderStatus.REJECTED
                                ? existing.getRejectReason() : RejectReason.NONE,
                        List.of());
            }

            Account account = accounts.get(accountId);
            if (account == null) {
                throw new TradingException(RejectReason.UNKNOWN_ACCOUNT, "unknown account: " + accountId);
            }

            long refPrice = referencePrice(symbol);
            Order order = newOrder(clientOrderId, accountId, symbol, side, type, price, quantity);

            // 风控（纯读，拒绝时不改变账户状态，也不写流水、不分配 orderId）。
            try {
                riskValidator.validate(account, order, refPrice);
            } catch (TradingException ex) {
                log.info("order rejected by risk: clientOrderId={}, account={}, reason={}, qty={}, snapshot={}",
                        clientOrderId, accountId, ex.getReason(), quantity, snapshot(accountId));
                throw ex;
            }

            // 分配标识并先持久化“已接受”订单，再改内存，保证重启可见。
            order.setOrderId(++orderIdSeq);
            order.setSeq(++seqCounter);
            eventStore.append(Event.orderAccepted(order));
            ordersById.put(order.getOrderId(), order);
            clientIndex.put(clientKey(accountId, clientOrderId), order);

            // 冻结：买单冻结最坏成本资金，卖单冻结可卖持仓。
            reserve(order, refPrice);

            // 撮合（市价单附加参考价滑点保护）。
            LongPredicate guard = p -> refPrice <= 0 || withinDeviation(p, refPrice);
            List<Match> matches = matchingEngine.match(order, book(symbol), guard);

            List<Long> fillIds = new ArrayList<>();
            for (Match m : matches) {
                fillIds.add(settleMatch(m));
            }

            // 市价单未成交的剩余：撤销并释放冻结；限价剩余已在簿。
            if (order.getType() == OrderType.MARKET && order.remainingQty() > 0) {
                long leftover = order.remainingQty();
                order.setStatus(OrderStatus.CANCELED);
                releaseReservation(order, leftover);
                eventStore.append(Event.orderCanceled(order));
                if (order.getFilledQty() == 0) {
                    throw new TradingException(RejectReason.MARKET_NO_LIQUIDITY,
                            "market order not filled, remaining canceled: " + leftover);
                }
            } else if (order.remainingQty() == 0) {
                order.setStatus(OrderStatus.FILLED);
            }

            assertInvariants("submit order " + order.getOrderId());
            log.info("order handled: orderId={}, clientOrderId={}, side={}, type={}, status={}, filled={}, remaining={}, fillIds={}, snapshot={}",
                    order.getOrderId(), clientOrderId, side, type, order.getStatus(),
                    order.getFilledQty(), order.remainingQty(), fillIds, snapshot(accountId));
            return new OrderResult(order, false, RejectReason.NONE, fillIds);
        }
    }

    private Order newOrder(String clientOrderId, String accountId, String symbol,
                           Side side, OrderType type, long price, long quantity) {
        Order order = new Order();
        order.setAccountId(accountId);
        order.setClientOrderId(clientOrderId);
        order.setSymbol(symbol);
        order.setSide(side);
        order.setType(type);
        order.setLimitPrice(type == OrderType.LIMIT ? price : 0L);
        order.setQuantity(quantity);
        order.setFilledQty(0L);
        order.setStatus(OrderStatus.NEW);
        order.setCreatedAt(System.currentTimeMillis());
        return order;
    }

    // ------------------------------------------------------------------
    // 撤单
    // ------------------------------------------------------------------

    public CancelResult cancel(long orderId) {
        synchronized (lock) {
            Order order = ordersById.get(orderId);
            if (order == null) {
                throw new TradingException(RejectReason.ORDER_NOT_FOUND, "order not found: " + orderId);
            }
            switch (order.getStatus()) {
                case FILLED -> throw new TradingException(RejectReason.ORDER_ALREADY_FILLED,
                        "order already fully filled: " + orderId);
                case CANCELED -> throw new TradingException(RejectReason.ORDER_ALREADY_CANCELED,
                        "order already canceled: " + orderId);
                default -> { }
            }
            long canceledQty = order.remainingQty();
            order.setStatus(OrderStatus.CANCELED);
            if (order.getType() == OrderType.LIMIT) {
                book(order.getSymbol()).remove(order);
            }
            releaseReservation(order, canceledQty);
            eventStore.append(Event.orderCanceled(order));
            assertInvariants("cancel order " + orderId);
            log.info("order canceled: orderId={}, clientOrderId={}, canceledQty={}, priorFilledQty={}, snapshot={}",
                    orderId, order.getClientOrderId(), canceledQty,
                    order.getFilledQty(), snapshot(order.getAccountId()));
            return new CancelResult(order, true, RejectReason.NONE, canceledQty);
        }
    }

    // ------------------------------------------------------------------
    // 改单
    // ------------------------------------------------------------------

    /**
     * 改单（原子 cancel-replace 语义）：仅允许仍挂盘（NEW 且有剩余）的限价单改价/改量。
     *
     * <p>规则：
     * <ul>
     *   <li>newQuantity 为改后订单总数量（含已成交部分），必须 &gt; filledQty；
     *       已成交部分不可逆，想清掉剩余请走撤单；</li>
     *   <li>改单成功后订单失去原时间优先：分配新接收序号，按改后价格重新排到队尾；</li>
     *   <li>改后价格与盘口交叉时立即按现有撮合规则成交（maker 价）；</li>
     *   <li>风控与下单同一套：先临时解冻旧占用（账户层可精确逆运算），按改后剩余量校验，
     *       拒绝则恢复旧冻结——订单簿不动、无流水，账户与订单跟没发生过一样；</li>
     *   <li>幂等键：orderId + clientAmendId，重复提交（含网络重试）只生效一次。</li>
     * </ul>
     */
    public AmendResult amendOrder(long orderId, String clientAmendId, long newPrice, long newQuantity) {
        synchronized (lock) {
            if (clientAmendId == null || clientAmendId.isBlank()) {
                throw new IllegalArgumentException("clientAmendId is required");
            }
            Order order = ordersById.get(orderId);
            if (order == null) {
                throw new TradingException(RejectReason.ORDER_NOT_FOUND, "order not found: " + orderId);
            }

            // 幂等：同一订单同一 clientAmendId 重复提交，返回当前状态，不重复改单/重复调整冻结。
            String amendKey = amendKey(orderId, clientAmendId);
            if (amendedKeys.contains(amendKey)) {
                log.info("idempotent amend hit: orderId={}, clientAmendId={}, status={}, filledQty={}, snapshot={}",
                        orderId, clientAmendId, order.getStatus(), order.getFilledQty(),
                        snapshot(order.getAccountId()));
                return new AmendResult(order, true, RejectReason.NONE, List.of());
            }

            if (order.getType() == OrderType.MARKET) {
                throw new TradingException(RejectReason.ORDER_NOT_AMENDABLE,
                        "market order is not amendable: " + orderId);
            }
            switch (order.getStatus()) {
                case FILLED -> throw new TradingException(RejectReason.ORDER_ALREADY_FILLED,
                        "order already fully filled: " + orderId);
                case CANCELED -> throw new TradingException(RejectReason.ORDER_ALREADY_CANCELED,
                        "order already canceled: " + orderId);
                default -> { }
            }
            long filled = order.getFilledQty();
            if (newQuantity <= filled) {
                throw new TradingException(RejectReason.INVALID_QUANTITY,
                        "new quantity " + newQuantity + " must exceed filledQty " + filled
                                + " (use cancel to close the remaining)");
            }

            Account account = accounts.get(order.getAccountId());
            long refPrice = referencePrice(order.getSymbol());
            long oldRemaining = order.remainingQty();

            // 风控（与下单同一套）：先临时解冻旧占用，按改后剩余量构造探针单校验；
            // 拒绝则恢复旧冻结——账户精确复原、订单簿不动、不写流水。
            releaseReservation(order, oldRemaining);
            Order probe = amendProbe(order, newPrice, newQuantity - filled);
            try {
                riskValidator.validate(account, probe, refPrice);
            } catch (TradingException ex) {
                reserveQty(order, oldRemaining);
                log.info("amend rejected by risk: orderId={}, clientAmendId={}, reason={}, newPrice={}, newQty={}, snapshot={}",
                        orderId, clientAmendId, ex.getReason(), newPrice, newQuantity,
                        snapshot(order.getAccountId()));
                throw ex;
            }

            // 通过风控：出簿（旧价档位）→ 改价改量换新序号 → 落流水 → 重新冻结 → 即时撮合。
            book(order.getSymbol()).remove(order);
            order.setLimitPrice(newPrice);
            order.setQuantity(newQuantity);
            order.setSeq(++seqCounter);
            Amend amend = new Amend(orderId, clientAmendId, newPrice, newQuantity,
                    order.getSeq(), System.currentTimeMillis());
            eventStore.append(Event.orderAmended(amend));
            amendedKeys.add(amendKey);
            reserveQty(order, order.remainingQty());

            // 改后价格与盘口交叉时立即成交（taker 角色，成交价取 maker 价）。
            LongPredicate guard = p -> refPrice <= 0 || withinDeviation(p, refPrice);
            List<Match> matches = matchingEngine.match(order, book(order.getSymbol()), guard);
            List<Long> fillIds = new ArrayList<>();
            for (Match m : matches) {
                fillIds.add(settleMatch(m));
            }

            assertInvariants("amend order " + orderId);
            log.info("amend handled: orderId={}, clientAmendId={}, newPrice={}, newQty={}, filled={}, remaining={}, status={}, fillIds={}, snapshot={}",
                    orderId, clientAmendId, newPrice, newQuantity, order.getFilledQty(),
                    order.remainingQty(), order.getStatus(), fillIds, snapshot(order.getAccountId()));
            return new AmendResult(order, false, RejectReason.NONE, fillIds);
        }
    }

    /** 风控探针单：以改后剩余数量为数量的限价单，复用下单风控规则。 */
    private Order amendProbe(Order base, long newPrice, long newRemaining) {
        Order probe = new Order();
        probe.setAccountId(base.getAccountId());
        probe.setSymbol(base.getSymbol());
        probe.setSide(base.getSide());
        probe.setType(OrderType.LIMIT);
        probe.setLimitPrice(newPrice);
        probe.setQuantity(newRemaining);
        probe.setFilledQty(0L);
        probe.setStatus(OrderStatus.NEW);
        return probe;
    }

    /** 改单冻结（仅限价单）：买单按改后限价冻结资金；卖单冻结可卖持仓。 */
    private void reserveQty(Order order, long qty) {
        if (qty <= 0) {
            return;
        }
        Account acc = accounts.get(order.getAccountId());
        if (order.getSide() == Side.BUY) {
            acc.setAvailableCash(acc.getAvailableCash()
                    - qty * order.getLimitPrice() / RiskValidator.SCALE);
        } else {
            Position pos = acc.positionOf(order.getSymbol());
            pos.setAvailable(pos.getAvailable() - qty);
        }
    }

    // ------------------------------------------------------------------
    // 外部成交投递（fillId+owner 幂等）
    // ------------------------------------------------------------------

    public boolean deliverFill(long fillId, long matchId, long price, long quantity,
                               String symbol, String ownerAccountId, Side ownerSide,
                               boolean roleTaker, long ownerOrderId,
                               long takerOrderId, long makerOrderId, long timestamp) {
        synchronized (lock) {
            if (settledFillKeys.contains(fillKey(fillId, ownerAccountId))) {
                log.info("duplicate fill delivery ignored: fillId={}, owner={}, orderId={}",
                        fillId, ownerAccountId, ownerOrderId);
                return false;
            }
            Fill fill = new Fill(fillId, matchId, price, quantity, symbol, ownerAccountId,
                    ownerSide, roleTaker, ownerOrderId, takerOrderId, makerOrderId,
                    timestamp > 0 ? timestamp : System.currentTimeMillis());
            eventStore.append(Event.fill(fill));
            // 外部成交不经过下单冻结流程，按无冻结直接结算（与重放口径一致）。
            settleReplay(fill);
            settledFillKeys.add(fillKey(fillId, ownerAccountId));
            // 外部投递不经过撮合引擎，订单数量由流水推进。
            advanceOrder(ownerOrderId, quantity);
            assertInvariants("deliver fill " + fillId);
            log.info("external fill applied: fillId={}, owner={}, side={}, qty={}, price={}, snapshot={}",
                    fillId, ownerAccountId, ownerSide, quantity, price, snapshot(ownerAccountId));
            return true;
        }
    }

    // ------------------------------------------------------------------
    // 撮合后的结算
    // ------------------------------------------------------------------

    private long settleMatch(Match m) {
        long fillId = ++fillIdSeq;
        long matchId = ++matchIdSeq;
        long ts = System.currentTimeMillis();
        Order taker = m.taker();
        Order maker = m.maker();

        Fill takerLeg = new Fill(fillId, matchId, m.price(), m.quantity(), taker.getSymbol(),
                taker.getAccountId(), taker.getSide(), true, taker.getOrderId(),
                taker.getOrderId(), maker.getOrderId(), ts);
        Fill makerLeg = new Fill(fillId, matchId, m.price(), m.quantity(), maker.getSymbol(),
                maker.getAccountId(), maker.getSide(), false, maker.getOrderId(),
                taker.getOrderId(), maker.getOrderId(), ts);

        // 先落盘两条账户侧流水（共享 fillId），再生效。
        eventStore.append(Event.fill(takerLeg));
        eventStore.append(Event.fill(makerLeg));
        applyFill(takerLeg);
        applyFill(makerLeg);

        if (taker.remainingQty() == 0 && taker.getStatus() == OrderStatus.NEW) {
            taker.setStatus(OrderStatus.FILLED);
        }
        if (maker.remainingQty() == 0 && maker.getStatus() == OrderStatus.NEW) {
            maker.setStatus(OrderStatus.FILLED);
        }
        lastPrice.put(taker.getSymbol(), m.price());
        return fillId;
    }

    private void applyFill(Fill f) {
        // 实时路径：撮合引擎已推进订单 filledQty，这里只做幂等登记与资金/持仓结算。
        if (!settledFillKeys.add(fillKey(f.fillId(), f.ownerAccountId()))) {
            log.warn("fill leg already settled, skip: fillId={}, owner={}",
                    f.fillId(), f.ownerAccountId());
            return;
        }
        settle(f);
    }

    /**
     * 重放结算：不重建下单时的冻结，直接让账户从种子状态累计到最终状态。
     * 买：cash/availableCash 扣实际成交额，持仓两栏同步增加；
     * 卖：cash/availableCash 增成交额，总持仓减少，可卖持仓同步减少。
     */
    private void settleReplay(Fill f) {
        Account acc = accounts.get(f.ownerAccountId());
        if (acc == null) {
            throw new TradingException(RejectReason.UNKNOWN_ACCOUNT,
                    "unknown account on replay fill: " + f.ownerAccountId());
        }
        long notional = f.price() * f.quantity() / RiskValidator.SCALE;
        Position pos = acc.positionOf(f.symbol());
        if (f.ownerSide() == Side.BUY) {
            acc.setCash(acc.getCash() - notional);
            acc.setAvailableCash(acc.getAvailableCash() - notional);
            pos.setQuantity(pos.getQuantity() + f.quantity());
            pos.setAvailable(pos.getAvailable() + f.quantity());
        } else {
            acc.setCash(acc.getCash() + notional);
            acc.setAvailableCash(acc.getAvailableCash() + notional);
            pos.setQuantity(pos.getQuantity() - f.quantity());
            pos.setAvailable(pos.getAvailable() - f.quantity());
        }
    }

    /**
     * 资金/持仓结算（约定见 {@link Account}）：
     * <ul>
     *   <li>买单：下单冻结 worstNotional；成交按实际 notional 扣款，
     *       差额 (worstPrice-fillPrice)*qty 回补可用现金；持仓总量与可卖量同步增加；</li>
     *   <li>卖单：下单冻结 qty 持仓（available -= qty）；成交时总量减少 qty，
     *       available 不变（仍为扣减后的值），现金与可用现金同时增加 notional。</li>
     * </ul>
     */
    private void settle(Fill f) {
        Account acc = accounts.get(f.ownerAccountId());
        if (acc == null) {
            throw new TradingException(RejectReason.UNKNOWN_ACCOUNT,
                    "unknown account on fill: " + f.ownerAccountId());
        }
        long notional = f.price() * f.quantity() / RiskValidator.SCALE;
        if (f.ownerSide() == Side.BUY) {
            long worstPrice = orderWorstPrice(f.ownerOrderId(), f.price());
            long frozenNotional = worstPrice * f.quantity() / RiskValidator.SCALE;
            // 冻结发生在下单时（availableCash 已减 frozenNotional，cash 未动）：
            // 成交只扣实际成交额；冻结与实际额之差（含 0）回到可用现金。
            acc.setCash(acc.getCash() - notional);
            acc.setAvailableCash(acc.getAvailableCash() + (frozenNotional - notional));
            Position pos = acc.positionOf(f.symbol());
            pos.setQuantity(pos.getQuantity() + f.quantity());
            pos.setAvailable(pos.getAvailable() + f.quantity());
        } else {
            acc.setCash(acc.getCash() + notional);
            acc.setAvailableCash(acc.getAvailableCash() + notional);
            Position pos = acc.positionOf(f.symbol());
            pos.setQuantity(pos.getQuantity() - f.quantity());
        }
    }

    /** 下单时该订单使用的最坏价（冻结依据）；限价单为限价，市价单为参考价上浮保护带。 */
    private long orderWorstPrice(long orderId, long fallback) {
        Order o = ordersById.get(orderId);
        if (o == null) {
            return fallback;
        }
        if (o.getType() == OrderType.LIMIT) {
            return o.getLimitPrice();
        }
        long ref = referencePrice(o.getSymbol());
        long worst = marketWorstBuyPrice(ref);
        return worst > 0 ? worst : fallback;
    }

    /** 下单冻结：买单冻结最坏成本资金；卖单冻结持仓。 */
    private void reserve(Order order, long refPrice) {
        Account acc = accounts.get(order.getAccountId());
        if (order.getSide() == Side.BUY) {
            long worstPrice = order.getType() == OrderType.LIMIT
                    ? order.getLimitPrice() : marketWorstBuyPrice(refPrice);
            acc.setAvailableCash(acc.getAvailableCash()
                    - order.getQuantity() * worstPrice / RiskValidator.SCALE);
        } else {
            Position pos = acc.positionOf(order.getSymbol());
            pos.setAvailable(pos.getAvailable() - order.getQuantity());
        }
    }

    /** 撤单/市价剩余：回补未成交部分对应的冻结。 */
    private void releaseReservation(Order order, long releasedQty) {
        if (releasedQty <= 0) {
            return;
        }
        Account acc = accounts.get(order.getAccountId());
        if (order.getSide() == Side.BUY) {
            long worstPrice = order.getType() == OrderType.LIMIT
                    ? order.getLimitPrice() : marketWorstBuyPrice(referencePrice(order.getSymbol()));
            acc.setAvailableCash(acc.getAvailableCash()
                    + releasedQty * worstPrice / RiskValidator.SCALE);
        } else {
            Position pos = acc.positionOf(order.getSymbol());
            pos.setAvailable(pos.getAvailable() + releasedQty);
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public Order getOrder(long orderId) {
        synchronized (lock) {
            return ordersById.get(orderId);
        }
    }

    public AccountSnapshot snapshot(String accountId) {
        synchronized (lock) {
            Account acc = accounts.get(accountId);
            if (acc == null) {
                throw new TradingException(RejectReason.UNKNOWN_ACCOUNT, "unknown account: " + accountId);
            }
            Map<String, long[]> positions = new LinkedHashMap<>();
            acc.getPositions().forEach((s, p) ->
                    positions.put(s, new long[]{p.getQuantity(), p.getAvailable()}));
            return new AccountSnapshot(accountId, acc.getCash(), acc.getAvailableCash(),
                    Collections.unmodifiableMap(positions));
        }
    }

    public OrderBook orderBook(String symbol) {
        synchronized (lock) {
            return books.get(symbol);
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private OrderBook book(String symbol) {
        return books.computeIfAbsent(symbol, s -> new OrderBook());
    }

    private long referencePrice(String symbol) {
        return lastPrice.getOrDefault(symbol, referenceHints.getOrDefault("ref:" + symbol, 0L));
    }

    /** 尚无成交时设置参考价（测试/初始化用）。 */
    public void setReferencePrice(String symbol, long price) {
        synchronized (lock) {
            referenceHints.put("ref:" + symbol, price);
        }
    }

    private long marketWorstBuyPrice(long refPrice) {
        if (refPrice <= 0) {
            return 0;
        }
        return refPrice * (10000L + properties.getMaxDeviationBps()) / 10000L;
    }

    private boolean withinDeviation(long price, long refPrice) {
        long deviation = Math.abs(price - refPrice) * 10000L / refPrice;
        return deviation <= properties.getMaxDeviationBps();
    }

    private String clientKey(String accountId, String clientOrderId) {
        return accountId + "|" + clientOrderId;
    }

    private String fillKey(long fillId, String ownerAccountId) {
        return fillId + "|" + ownerAccountId;
    }

    private String amendKey(long orderId, String clientAmendId) {
        return orderId + "|" + clientAmendId;
    }

    private Side parseSide(String text) {
        try {
            return Side.valueOf(text.toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("side must be BUY or SELL: " + text);
        }
    }

    private OrderType parseType(String text) {
        try {
            return OrderType.valueOf(text.toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("type must be LIMIT or MARKET: " + text);
        }
    }

    /**
     * 守恒不变量：任意账户任意时刻
     * availableCash &ge; minAvailableCash；
     * 0 &le; position.available &le; position.quantity。
     * 违规立即抛错，避免静默资损。
     */
    private void assertInvariants(String where) {
        for (Account acc : accounts.values()) {
            if (acc.getAvailableCash() < properties.getMinAvailableCash()) {
                throw new IllegalStateException("invariant violated at " + where
                        + ": negative available cash for " + acc.getAccountId()
                        + " -> " + acc.getAvailableCash());
            }
            for (Map.Entry<String, Position> e : acc.getPositions().entrySet()) {
                Position p = e.getValue();
                if (p.getAvailable() < 0 || p.getAvailable() > p.getQuantity()) {
                    throw new IllegalStateException("invariant violated at " + where
                            + ": bad available position for " + acc.getAccountId() + ":" + e.getKey()
                            + " qty=" + p.getQuantity() + " avail=" + p.getAvailable());
                }
            }
        }
    }
}
