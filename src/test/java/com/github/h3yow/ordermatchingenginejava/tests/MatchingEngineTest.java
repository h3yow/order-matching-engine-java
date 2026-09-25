package com.github.h3yow.ordermatchingenginejava.tests;

import static org.junit.jupiter.api.Assertions.*;

import com.github.h3yow.ordermatchingenginejava.account.Account;
import com.github.h3yow.ordermatchingenginejava.account.AccountService;
import com.github.h3yow.ordermatchingenginejava.domain.OrderStatus;
import com.github.h3yow.ordermatchingenginejava.domain.OrderType;
import com.github.h3yow.ordermatchingenginejava.domain.RejectReason;
import com.github.h3yow.ordermatchingenginejava.domain.Side;
import com.github.h3yow.ordermatchingenginejava.engine.MatchingEngine;
import com.github.h3yow.ordermatchingenginejava.engine.OrderResult;
import com.github.h3yow.ordermatchingenginejava.persistence.Journal;
import com.github.h3yow.ordermatchingenginejava.risk.RiskChecker;
import com.github.h3yow.ordermatchingenginejava.risk.RiskLimits;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 撮合、撤单、风控与幂等的基本行为测试。 */
class MatchingEngineTest {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngineTest.class);

    @TempDir
    Path tempDir;

    RiskLimits limits;
    AccountService accountService;
    MatchingEngine engine;

    @BeforeEach
    void setUp() {
        limits = new RiskLimits();
        accountService = new AccountService(limits);
        engine = new MatchingEngine(accountService, new RiskChecker(limits), limits,
                new Journal(tempDir.resolve("journal.log").toString()));
    }

    private Account acct(String id) {
        return accountService.getOrCreate(id);
    }

    private void logDecision(String tag, String orderId, String basis, String... accountIds) {
        StringBuilder sb = new StringBuilder();
        for (String id : accountIds) {
            sb.append(' ').append(acct(id).snapshot());
        }
        log.info("[ASSERT] {} orderId={} 判定依据: {}{}", tag, orderId, basis, sb);
    }

    @Test
    void limitOrdersCrossPriceTimePriority() {
        // 两个卖单：同价先到者优先
        OrderResult s1 = engine.submitOrder("c-s1", "seller", Side.SELL, OrderType.LIMIT, 10000L, 100);
        OrderResult s2 = engine.submitOrder("c-s2", "seller", Side.SELL, OrderType.LIMIT, 10000L, 100);
        assertTrue(s1.isAccepted() && s2.isAccepted());

        OrderResult b1 = engine.submitOrder("c-b1", "buyer", Side.BUY, OrderType.LIMIT, 10000L, 150);
        assertTrue(b1.isAccepted());
        assertEquals(2, b1.getTrades().size(), "应先吃掉先到的卖单再吃掉部分第二个卖单");
        assertEquals(100, b1.getTrades().get(0).getQuantity());
        assertEquals(s1.getOrderId(), b1.getTrades().get(0).getSellOrderId(), "时间优先：先成交先到的卖单");
        assertEquals(50, b1.getTrades().get(1).getQuantity());
        assertEquals(10000L, b1.getTrades().get(0).getPrice(), "成交价为对手方限价");

        assertEquals(OrderStatus.FILLED, engine.getOrder(s1.getOrderId()).getStatus());
        assertEquals(OrderStatus.PARTIALLY_FILLED, engine.getOrder(s2.getOrderId()).getStatus());
        assertEquals(OrderStatus.FILLED, engine.getOrder(b1.getOrderId()).getStatus());
        logDecision("价格时间优先", b1.getOrderId(), "同价位先到卖单先成交，成交价=对手限价",
                "buyer", "seller");
    }

    @Test
    void marketOrderMatchesAtBestPrices() {
        engine.submitOrder("c-s1", "seller", Side.SELL, OrderType.LIMIT, 10000L, 100);
        engine.submitOrder("c-s2", "seller", Side.SELL, OrderType.LIMIT, 10100L, 100);

        OrderResult mb = engine.submitOrder("c-b1", "buyer", Side.BUY, OrderType.MARKET, null, 150);
        assertTrue(mb.isAccepted());
        assertEquals(2, mb.getTrades().size());
        assertEquals(10000L, mb.getTrades().get(0).getPrice(), "市价单先吃最优卖价");
        assertEquals(10100L, mb.getTrades().get(1).getPrice());
        assertEquals(OrderStatus.FILLED, engine.getOrder(mb.getOrderId()).getStatus());
        logDecision("市价单撮合", mb.getOrderId(), "按卖一→卖二顺序成交", "buyer", "seller");
    }

    @Test
    void partialFillThenCancelRest() {
        engine.submitOrder("c-s1", "seller", Side.SELL, OrderType.LIMIT, 10000L, 60);
        OrderResult b = engine.submitOrder("c-b1", "buyer", Side.BUY, OrderType.LIMIT, 10000L, 100);
        assertEquals(OrderStatus.PARTIALLY_FILLED, engine.getOrder(b.getOrderId()).getStatus());

        Account buyer = acct("buyer");
        long cashBefore = buyer.getCashAvailable() + buyer.getCashFrozen();
        OrderResult cancel = engine.cancelOrder(b.getOrderId());
        assertTrue(cancel.isAccepted());
        assertEquals(OrderStatus.CANCELED, engine.getOrder(b.getOrderId()).getStatus());
        assertEquals(0, buyer.getCashFrozen(), "撤单后冻结资金应全部解冻");
        assertEquals(cashBefore, buyer.getCashAvailable() + buyer.getCashFrozen(),
                "撤单只解冻不扣款，资金总量守恒");
        logDecision("部分成交后撤单", b.getOrderId(), "剩余40股解冻，已成交60股保持结算", "buyer");

        // 已撤订单再次撤单 → 可区分拒绝
        OrderResult again = engine.cancelOrder(b.getOrderId());
        assertFalse(again.isAccepted());
        assertEquals(RejectReason.ORDER_ALREADY_CANCELED, again.getRejectReason());
    }

    @Test
    void cancelRejectsAreDistinguishable() {
        OrderResult notFound = engine.cancelOrder("O999");
        assertEquals(RejectReason.ORDER_NOT_FOUND, notFound.getRejectReason());

        engine.submitOrder("c-s1", "seller", Side.SELL, OrderType.LIMIT, 10000L, 100);
        OrderResult b = engine.submitOrder("c-b1", "buyer", Side.BUY, OrderType.LIMIT, 10000L, 100);
        assertEquals(OrderStatus.FILLED, engine.getOrder(b.getOrderId()).getStatus());
        OrderResult filledCancel = engine.cancelOrder(b.getOrderId());
        assertEquals(RejectReason.ORDER_ALREADY_FILLED, filledCancel.getRejectReason());
        logDecision("撤单拒绝可区分", b.getOrderId(), "NOT_FOUND / ALREADY_FILLED / ALREADY_CANCELED 互不相同");
    }

    @Test
    void duplicateClientOrderIdRejectedWithoutDoubleEffect() {
        OrderResult first = engine.submitOrder("c-dup", "seller", Side.SELL, OrderType.LIMIT, 10000L, 100);
        assertTrue(first.isAccepted());
        Account seller = acct("seller");
        long frozenAfterFirst = seller.getPositionFrozen();

        OrderResult dup = engine.submitOrder("c-dup", "seller", Side.SELL, OrderType.LIMIT, 10000L, 100);
        assertFalse(dup.isAccepted());
        assertEquals(RejectReason.DUPLICATE_CLIENT_ORDER_ID, dup.getRejectReason());
        assertEquals(frozenAfterFirst, seller.getPositionFrozen(), "重复提交不得重复冻结");
        logDecision("重复提交幂等", first.getOrderId(), "clientOrderId 去重，无重复冻结/成交", "seller");
    }

    @Test
    void riskRejectsWithDistinctReasonsAndNoStateChange() {
        // 调低默认持仓，使持仓校验先于数量校验被触及
        limits.setDefaultPosition(500);
        // 单笔数量超限
        OrderResult tooBig = engine.submitOrder("c-r1", "buyer", Side.BUY, OrderType.LIMIT,
                10000L, limits.getMaxOrderQuantity() + 1);
        assertEquals(RejectReason.EXCEED_MAX_ORDER_QUANTITY, tooBig.getRejectReason());

        // 价格偏离过大（参考价10000，容忍20%）
        OrderResult badPrice = engine.submitOrder("c-r2", "buyer", Side.BUY, OrderType.LIMIT,
                20000L, 10);
        assertEquals(RejectReason.PRICE_OUT_OF_RANGE, badPrice.getRejectReason());

        // 余额不足：限价10000 * 2000 > 默认资金
        OrderResult noCash = engine.submitOrder("c-r3", "buyer", Side.BUY, OrderType.LIMIT,
                10000L, limits.getDefaultCash() / 10000 + 1);
        assertEquals(RejectReason.INSUFFICIENT_BALANCE, noCash.getRejectReason());

        // 持仓不足
        OrderResult noPos = engine.submitOrder("c-r4", "seller", Side.SELL, OrderType.LIMIT,
                10000L, 501);
        assertEquals(RejectReason.INSUFFICIENT_POSITION, noPos.getRejectReason());

        Account buyer = acct("buyer");
        assertEquals(limits.getDefaultCash(), buyer.getCashAvailable(), "被拒订单不得改变账户状态");
        assertEquals(0, buyer.getCashFrozen());
        logDecision("风控拒绝", "c-r1..c-r4",
                "数量/价格/余额/持仓四种原因可区分且账户状态不变", "buyer", "seller");
    }

    @Test
    void conservationAfterTrades() {
        engine.submitOrder("c-s1", "seller", Side.SELL, OrderType.LIMIT, 10000L, 100);
        engine.submitOrder("c-b1", "buyer", Side.BUY, OrderType.LIMIT, 10000L, 100);

        Account buyer = acct("buyer");
        Account seller = acct("seller");
        long totalCash = buyer.getCashAvailable() + buyer.getCashFrozen()
                + seller.getCashAvailable() + seller.getCashFrozen();
        long totalPos = buyer.getPositionAvailable() + buyer.getPositionFrozen()
                + seller.getPositionAvailable() + seller.getPositionFrozen();
        assertEquals(2 * limits.getDefaultCash(), totalCash, "成交后现金总量守恒");
        assertEquals(2 * limits.getDefaultPosition(), totalPos, "成交后持仓总量守恒");
        assertEquals(0, buyer.getCashFrozen());
        assertEquals(0, seller.getPositionFrozen());
        logDecision("状态守恒", "c-b1", "现金与持仓总量在成交前后不变", "buyer", "seller");
    }
}
