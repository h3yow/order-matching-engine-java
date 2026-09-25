package com.github.h3yow.ordermatchingenginejava.tests;

import static org.junit.jupiter.api.Assertions.*;

import com.github.h3yow.ordermatchingenginejava.account.Account;
import com.github.h3yow.ordermatchingenginejava.account.AccountService;
import com.github.h3yow.ordermatchingenginejava.domain.Order;
import com.github.h3yow.ordermatchingenginejava.domain.OrderStatus;
import com.github.h3yow.ordermatchingenginejava.domain.OrderType;
import com.github.h3yow.ordermatchingenginejava.domain.Side;
import com.github.h3yow.ordermatchingenginejava.domain.Trade;
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

/** 重放一致性与成交重复投递幂等测试。 */
class ReplayTest {

    private static final Logger log = LoggerFactory.getLogger(ReplayTest.class);

    @TempDir
    Path tempDir;

    RiskLimits limits;
    AccountService accountService;
    MatchingEngine engine;
    String journalPath;

    @BeforeEach
    void setUp() {
        limits = new RiskLimits();
        journalPath = tempDir.resolve("journal.log").toString();
        accountService = new AccountService(limits);
        engine = newEngine(accountService);
    }

    private MatchingEngine newEngine(AccountService accounts) {
        return new MatchingEngine(accounts, new RiskChecker(limits), limits,
                new Journal(journalPath));
    }

    /** 构造一段包含挂单、成交、部分成交后撤单的历史。 */
    private void buildHistory() {
        engine.submitOrder("c-s1", "seller", Side.SELL, OrderType.LIMIT, 10000L, 100);
        engine.submitOrder("c-s2", "seller", Side.SELL, OrderType.LIMIT, 10100L, 50);
        // 部分成交：买 120，吃掉 s1 全部 + s2 部分，剩余 20 挂簿后撤单
        OrderResult b1 = engine.submitOrder("c-b1", "buyer", Side.BUY, OrderType.LIMIT, 10100L, 120);
        engine.cancelOrder(b1.getOrderId());
        // 一笔未成交的挂簿买单（重启后应仍在簿中）
        engine.submitOrder("c-b2", "buyer2", Side.BUY, OrderType.LIMIT, 10000L, 30);
    }

    @Test
    void replayRestoresIdenticalVisibleState() {
        buildHistory();

        Account buyerBefore = accountService.getOrCreate("buyer");
        Account sellerBefore = accountService.getOrCreate("seller");
        Account buyer2Before = accountService.getOrCreate("buyer2");
        String snapBefore = buyerBefore.snapshot() + sellerBefore.snapshot()
                + buyer2Before.snapshot();
        long lastPriceBefore = engine.getLastTradePrice();
        log.info("[ASSERT] 重放前快照 lastPrice={} {}", lastPriceBefore, snapBefore);

        // 模拟重启：全新引擎与账户表，依据同一流水重放
        AccountService accounts2 = new AccountService(limits);
        MatchingEngine engine2 = newEngine(accounts2);
        engine2.replay();

        assertEquals(snapBefore,
                accounts2.getOrCreate("buyer").snapshot()
                        + accounts2.getOrCreate("seller").snapshot()
                        + accounts2.getOrCreate("buyer2").snapshot(),
                "重放后账户可见状态必须与重启前一致");
        assertEquals(lastPriceBefore, engine2.getLastTradePrice(), "最近成交价应恢复");

        Order resting = engine2.getOrder(engine.getOrder("O4") != null ? "O4" : "O4");
        assertNotNull(resting);
        assertEquals(OrderStatus.OPEN, resting.getStatus(), "未成交挂簿单重启后应保持 OPEN");
        log.info("[ASSERT] 重放后快照一致 判定依据: 账户快照逐字段相同 {}",
                accounts2.getOrCreate("buyer").snapshot());

        // 重放后继续撮合应正常：新卖单与簿中买单成交
        OrderResult s3 = engine2.submitOrder("c-s3", "seller", Side.SELL, OrderType.LIMIT,
                10000L, 30);
        assertTrue(s3.isAccepted());
        assertEquals(1, s3.getTrades().size(), "重启后簿中残余买单应可继续成交");
        assertEquals(OrderStatus.FILLED, engine2.getOrder("O4").getStatus());
    }

    @Test
    void duplicateTradeDeliverySettlesOnlyOnce() {
        engine.submitOrder("c-s1", "seller", Side.SELL, OrderType.LIMIT, 10000L, 100);
        OrderResult b1 = engine.submitOrder("c-b1", "buyer", Side.BUY, OrderType.LIMIT,
                10000L, 100);
        Trade trade = b1.getTrades().get(0);

        Account buyer = accountService.getOrCreate("buyer");
        Account seller = accountService.getOrCreate("seller");
        String snapBefore = buyer.snapshot() + seller.snapshot();

        // 重复投递同一成交事件两次
        engine.applyTrade(trade);
        engine.applyTrade(trade);

        assertEquals(snapBefore, buyer.snapshot() + seller.snapshot(),
                "重复投递同一 tradeId 不得重复结算");
        assertEquals(OrderStatus.FILLED, engine.getOrder(b1.getOrderId()).getStatus(),
                "订单状态不得因重复投递而改变");
        log.info("[ASSERT] 重复投递幂等 tradeId={} 判定依据: 账户快照不变 {}",
                trade.getTradeId(), buyer.snapshot());
    }

    @Test
    void replayIsIdempotentWhenRunTwice() {
        buildHistory();
        AccountService accounts2 = new AccountService(limits);
        MatchingEngine engine2 = newEngine(accounts2);
        engine2.replay();
        String snapAfterFirst = accounts2.getOrCreate("buyer").snapshot()
                + accounts2.getOrCreate("seller").snapshot();
        engine2.replay(); // 再次重放（如异常重启后重复恢复）
        assertEquals(snapAfterFirst, accounts2.getOrCreate("buyer").snapshot()
                + accounts2.getOrCreate("seller").snapshot(),
                "重复重放不得重复结算");
        log.info("[ASSERT] 重复重放幂等 判定依据: 两次重放后账户快照相同 {}", snapAfterFirst);
    }
}
