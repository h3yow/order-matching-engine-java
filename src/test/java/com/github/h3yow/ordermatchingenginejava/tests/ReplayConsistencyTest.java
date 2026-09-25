package com.github.h3yow.ordermatchingenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.h3yow.ordermatchingenginejava.domain.Order;
import com.github.h3yow.ordermatchingenginejava.domain.OrderStatus;
import com.github.h3yow.ordermatchingenginejava.domain.Side;
import com.github.h3yow.ordermatchingenginejava.engine.OrderBook;
import com.github.h3yow.ordermatchingenginejava.service.AccountSnapshot;
import com.github.h3yow.ordermatchingenginejava.service.OrderResult;
import com.github.h3yow.ordermatchingenginejava.service.TradingService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 成交与重放一致性测试：
 * 1) 重启后依据订单/成交/撤单流水重放，订单簿与账户可见状态与重启前一致；
 * 2) 重复投递同一成交事件（同 fillId+owner）不重复结算；
 * 3) 买卖双方现金+持仓名义价值守恒。
 */
class ReplayConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(ReplayConsistencyTest.class);

    private record Harness(ConfigurableApplicationContext ctx, TradingService service,
                           Path eventDir) implements AutoCloseable {
        @Override
        public void close() {
            ctx.close();
        }
    }

    private Harness start(Path dir) {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(
                com.github.h3yow.ordermatchingenginejava.Application.class)
                .properties(
                        "spring.main.web-application-type=none",
                        "trading.event-log-dir=" + dir.resolve("events"),
                        "trading.seed-accounts.alice=10000000000",
                        "trading.seed-accounts.bob=10000000000",
                        "trading.seed-positions.alice__BTC=1000000",
                        "trading.max-order-quantity=1000000",
                        "trading.max-deviation-bps=500")
                .run();
        return new Harness(ctx, ctx.getBean(TradingService.class), dir.resolve("events"));
    }

    @Test
    @DisplayName("重启重放：部分成交+撤单后重启，订单簿与账户快照与重启前完全一致")
    void replayRestoresIdenticalVisibleState(@TempDir Path dir) {
        long restingSellId;
        long restingBuyId;
        AccountSnapshot aliceBefore;
        AccountSnapshot bobBefore;

        try (Harness h = start(dir)) {
            TradingService s = h.service();
            s.setReferencePrice("BTC", 10000);
            aliceBefore = s.snapshot("alice");
            bobBefore = s.snapshot("bob");

            // alice 卖 300 @10000；bob 买 100 @10000 成交，alice 剩 200
            OrderResult sell = s.submitOrder("rep-sell-1", "alice", "BTC",
                    "SELL", "LIMIT", 10000, 300);
            restingSellId = sell.order().getOrderId();
            OrderResult buy = s.submitOrder("rep-buy-1", "bob", "BTC",
                    "BUY", "LIMIT", 10000, 100);
            assertEquals(100, buy.order().getFilledQty());

            // bob 再挂一个买单价 9800，重启后应仍在簿
            OrderResult restBuy = s.submitOrder("rep-buy-rest", "bob", "BTC",
                    "BUY", "LIMIT", 9800, 50);
            restingBuyId = restBuy.order().getOrderId();

            // 撤掉 alice 剩余 200
            s.cancel(restingSellId);

            aliceBefore = s.snapshot("alice");
            bobBefore = s.snapshot("bob");
            OrderBook bookBefore = s.orderBook("BTC");
            int bidsBefore = bookBefore.bidsSnapshot().size();
            int asksBefore = bookBefore.asksSnapshot().size();
            log.info("[beforeRestart] 判定基准 alice={} bob={} bidLevels={} askLevels={}",
                    aliceBefore, bobBefore, bidsBefore, asksBefore);
        }

        // 模拟“重启”：用同一事件目录启动全新上下文
        try (Harness h2 = start(dir)) {
            TradingService s2 = h2.service();

            assertEquals(aliceBefore, s2.snapshot("alice"), "重放后 alice 快照必须一致");
            assertEquals(bobBefore, s2.snapshot("bob"), "重放后 bob 快照必须一致");

            Order sellOrder = s2.getOrder(restingSellId);
            assertEquals(OrderStatus.CANCELED, sellOrder.getStatus());
            assertEquals(100, sellOrder.getFilledQty());
            assertEquals(200, sellOrder.remainingQty());

            Order restBuy = s2.getOrder(restingBuyId);
            assertEquals(OrderStatus.NEW, restBuy.getStatus());
            assertEquals(0, restBuy.getFilledQty());

            OrderBook book = s2.orderBook("BTC");
            List<Order> asks = book.asksSnapshot();
            List<Order> bids = book.bidsSnapshot();
            assertTrue(asks.isEmpty(), "判定依据：卖单已全撤，卖盘为空");
            assertEquals(1, bids.size(), "判定依据：bob 的 9800 买单必须随重放回簿");
            assertSame(restBuy, bids.get(0));

            // 重放后幂等表同样恢复：重复 clientOrderId 仍命中
            OrderResult dup = s2.submitOrder("rep-buy-1", "bob", "BTC",
                    "BUY", "LIMIT", 10000, 100);
            assertTrue(dup.idempotent());
            assertEquals(bobBefore, s2.snapshot("bob"), "重放后重复提交仍不得改变账户");

            log.info("[afterRestart] alice={} bob={} bids={} asks={} 判定=可见状态与重启前一致",
                    s2.snapshot("alice"), s2.snapshot("bob"),
                    bids.size(), asks.size());
        }
    }

    @Test
    @DisplayName("重复投递同一成交事件：只结算一次，现金/持仓守恒")
    void duplicateFillDeliveryIsIdempotent(@TempDir Path dir) {
        try (Harness h = start(dir)) {
            TradingService s = h.service();

            // 先各下一笔限价单形成订单与账户（bob 买 100 @10000 全部成交，对手为 alice）
            OrderResult sell = s.submitOrder("ext-sell", "alice", "BTC",
                    "SELL", "LIMIT", 10000, 100);
            OrderResult buy = s.submitOrder("ext-buy", "bob", "BTC",
                    "BUY", "LIMIT", 10000, 100);
            assertEquals(OrderStatus.FILLED, buy.order().getStatus());

            // 外部通道重复投递 fillId=999001（bob 侧与 alice 侧各一次，模拟事件重投）
            long fillId = 999_001L;
            boolean firstBob = s.deliverFill(fillId, 700_001L, 10000, 10, "BTC",
                    "bob", Side.BUY, true, buy.order().getOrderId(),
                    buy.order().getOrderId(), sell.order().getOrderId(), 0L);
            assertTrue(firstBob, "首次投递应当结算");
            AccountSnapshot bobAfterFirst = s.snapshot("bob");

            boolean dupBob = s.deliverFill(fillId, 700_001L, 10000, 10, "BTC",
                    "bob", Side.BUY, true, buy.order().getOrderId(),
                    buy.order().getOrderId(), sell.order().getOrderId(), 0L);
            assertFalse(dupBob, "判定依据：相同 fillId+owner 重复投递必须被忽略");
            assertEquals(bobAfterFirst, s.snapshot("bob"), "重复投递后账户快照必须不变");

            // alice 侧首次投递生效，再次投递忽略
            boolean firstAlice = s.deliverFill(fillId, 700_001L, 10000, 10, "BTC",
                    "alice", Side.SELL, false, sell.order().getOrderId(),
                    buy.order().getOrderId(), sell.order().getOrderId(), 0L);
            assertTrue(firstAlice, "不同 owner 的同 fillId 腿是独立流水，应首次生效");
            AccountSnapshot aliceAfter = s.snapshot("alice");
            boolean dupAlice = s.deliverFill(fillId, 700_001L, 10000, 10, "BTC",
                    "alice", Side.SELL, false, sell.order().getOrderId(),
                    buy.order().getOrderId(), sell.order().getOrderId(), 0L);
            assertFalse(dupAlice);
            assertEquals(aliceAfter, s.snapshot("alice"));

            log.info("[duplicateDelivery] fillId={} 判定=fillId+owner 幂等 bob={} alice={}",
                    fillId, s.snapshot("bob"), s.snapshot("alice"));

            // 重启后已投递流水同样不被二次结算
        }
        try (Harness h2 = start(dir)) {
            TradingService s2 = h2.service();
            AccountSnapshot aliceRestart = s2.snapshot("alice");
            // 重放不改变状态即可（与重启前相同）；重启前 alice 快照在另一个上下文，
            // 这里通过“再次重复投递仍被忽略”间接验证幂等表随重放恢复。
            boolean afterRestart = s2.deliverFill(999_001L, 700_001L, 10000, 10, "BTC",
                    "alice", Side.SELL, false, 0L, 0L, 0L, 0L);
            assertFalse(afterRestart, "判定依据：重放后 fillId 幂等表必须恢复");
            assertEquals(aliceRestart, s2.snapshot("alice"));
            log.info("[duplicateDeliveryRestart] fillId=999001 重放后再投递判定=忽略 {}",
                    s2.snapshot("alice"));
        }
    }

    @Test
    @DisplayName("资金守恒：成交双方现金变化之和等于零（按成交名义额），持仓双边对冲")
    void conservationAcrossTrades(@TempDir Path dir) {
        try (Harness h = start(dir)) {
            TradingService s = h.service();
            s.setReferencePrice("BTC", 10000);

            long aliceCash0 = s.snapshot("alice").cash();
            long bobCash0 = s.snapshot("bob").cash();
            long alicePos0 = s.snapshot("alice").positions().getOrDefault("BTC", new long[2])[0];
            long bobPos0 = s.snapshot("bob").positions().getOrDefault("BTC", new long[2])[0];

            s.submitOrder("c-sell", "alice", "BTC", "SELL", "LIMIT", 10000, 300);
            s.submitOrder("c-buy1", "bob", "BTC", "BUY", "LIMIT", 10000, 100);
            s.submitOrder("c-buy2", "bob", "BTC", "BUY", "LIMIT", 10100, 120);

            long notional1 = 10000L * 100 / 10000L;
            long notional2 = 10000L * 120 / 10000L; // maker 价 10000
            long totalNotional = notional1 + notional2;

            AccountSnapshot alice = s.snapshot("alice");
            AccountSnapshot bob = s.snapshot("bob");
            assertEquals(aliceCash0 + totalNotional, alice.cash(), "卖方现金增加成交额");
            assertEquals(bobCash0 - totalNotional, bob.cash(), "买方现金减少成交额");
            assertEquals(aliceCash0 + bobCash0, alice.cash() + bob.cash(),
                    "判定依据：系统内现金总量守恒");
            assertEquals(alicePos0 - 220, alice.positions().get("BTC")[0]);
            assertEquals(bobPos0 + 220, bob.positions().get("BTC")[0]);
            assertEquals(alicePos0 + bobPos0,
                    alice.positions().get("BTC")[0] + bob.positions().get("BTC")[0],
                    "判定依据：标的持仓双边对冲守恒");

            log.info("[conservation] totalNotional={} alice={} bob={}",
                    totalNotional, alice, bob);
        }
    }
}
