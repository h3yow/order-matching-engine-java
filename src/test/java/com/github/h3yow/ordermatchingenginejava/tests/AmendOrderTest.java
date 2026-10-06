package com.github.h3yow.ordermatchingenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.h3yow.ordermatchingenginejava.domain.Order;
import com.github.h3yow.ordermatchingenginejava.domain.OrderStatus;
import com.github.h3yow.ordermatchingenginejava.domain.RejectReason;
import com.github.h3yow.ordermatchingenginejava.domain.TradingException;
import com.github.h3yow.ordermatchingenginejava.service.AccountSnapshot;
import com.github.h3yow.ordermatchingenginejava.service.AmendResult;
import com.github.h3yow.ordermatchingenginejava.service.OrderResult;
import com.github.h3yow.ordermatchingenginejava.service.TradingService;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 改单（amend）测试：成功路径与冻结重算、时间优先丧失、即时成交、
 * 可区分拒绝、幂等、并发竞争、重启重放一致性。
 * 日志打印订单标识、判定依据与账户快照。
 */
class AmendOrderTest {

    private static final Logger log = LoggerFactory.getLogger(AmendOrderTest.class);

    private static final String BTC = "BTC";

    private record Harness(ConfigurableApplicationContext ctx, TradingService service)
            implements AutoCloseable {
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
                        "trading.seed-accounts.carol=10000000000",
                        "trading.seed-accounts.erin=5000",
                        "trading.seed-accounts.frank=0",
                        "trading.seed-positions.alice__BTC=1000000",
                        "trading.seed-positions.frank__BTC=5000",
                        "trading.max-order-quantity=1000000",
                        "trading.max-deviation-bps=500")
                .run();
        return new Harness(ctx, ctx.getBean(TradingService.class));
    }

    @Test
    @DisplayName("改单成功路径：改价/加量/减量后冻结精确重算，现金与持仓守恒")
    void amendRecomputesReservation(@TempDir Path dir) {
        try (Harness h = start(dir)) {
            TradingService s = h.service();
            s.setReferencePrice(BTC, 10000);

            // ---- 买单：冻结 = 剩余数量 * 限价 ----
            OrderResult buy = s.submitOrder("am-buy-1", "bob", BTC, "BUY", "LIMIT", 10000, 100);
            long buyId = buy.order().getOrderId();
            assertEquals(10_000_000_000L - 100, s.snapshot("bob").availableCash(),
                    "下买单冻结 100*10000/10000=100");

            // 加量 100 -> 150：冻结变 150
            AmendResult a1 = s.amendOrder(buyId, "am-buy-a1", 10000, 150);
            assertFalse(a1.idempotent());
            assertEquals(150, a1.order().getQuantity());
            assertEquals(10_000_000_000L - 150, s.snapshot("bob").availableCash(),
                    "判定依据：加量后冻结=150*10000/10000");

            // 改价 10000 -> 9800（偏离参考价 2%，带内）：冻结变 150*9800/10000=147
            AmendResult a2 = s.amendOrder(buyId, "am-buy-a2", 9800, 150);
            assertEquals(9800, a2.order().getLimitPrice());
            assertEquals(10_000_000_000L - 147, s.snapshot("bob").availableCash(),
                    "判定依据：改价后冻结=150*9800/10000");

            // 减量 150 -> 50：冻结变 50*9800/10000=49
            AmendResult a3 = s.amendOrder(buyId, "am-buy-a3", 9800, 50);
            assertEquals(50, a3.order().getQuantity());
            assertEquals(10_000_000_000L - 49, s.snapshot("bob").availableCash(),
                    "判定依据：减量后冻结=50*9800/10000");
            assertEquals(10_000_000_000L, s.snapshot("bob").cash(),
                    "未成交前现金总额不变，只动可用余额");

            // ---- 卖单：冻结 = 剩余数量持仓 ----
            OrderResult sell = s.submitOrder("am-sell-1", "alice", BTC, "SELL", "LIMIT", 10000, 300);
            long sellId = sell.order().getOrderId();
            assertEquals(999_700, s.snapshot("alice").positions().get(BTC)[1],
                    "下卖单冻结 300 可卖持仓");

            // 减量 300 -> 200：回补 100
            s.amendOrder(sellId, "am-sell-a1", 10000, 200);
            assertEquals(999_800, s.snapshot("alice").positions().get(BTC)[1],
                    "判定依据：卖单减量后冻结=200");
            // 加量 200 -> 400：再冻 200
            s.amendOrder(sellId, "am-sell-a2", 10000, 400);
            assertEquals(999_600, s.snapshot("alice").positions().get(BTC)[1],
                    "判定依据：卖单加量后冻结=400");
            assertEquals(1_000_000, s.snapshot("alice").positions().get(BTC)[0],
                    "未成交前持仓总量不变");

            log.info("[amendReserve] buyOrder={} sellOrder={} 判定=冻结随改单精确重算 bob={} alice={}",
                    buyId, sellId, s.snapshot("bob"), s.snapshot("alice"));
        }
    }

    @Test
    @DisplayName("改单失去时间优先：同价改单排到队尾，成交顺序按新排队")
    void amendLosesTimePriority(@TempDir Path dir) {
        try (Harness h = start(dir)) {
            TradingService s = h.service();
            s.setReferencePrice(BTC, 10000);

            // bob 先挂、carol 后挂，同价 9900
            OrderResult bobBuy = s.submitOrder("tp-buy-bob", "bob", BTC, "BUY", "LIMIT", 9900, 100);
            OrderResult carolBuy = s.submitOrder("tp-buy-carol", "carol", BTC, "BUY", "LIMIT", 9900, 100);
            long bobId = bobBuy.order().getOrderId();
            long carolId = carolBuy.order().getOrderId();
            List<Order> bids0 = s.orderBook(BTC).bidsSnapshot();
            assertEquals(List.of(bobId, carolId),
                    bids0.stream().map(Order::getOrderId).toList(),
                    "改单前同价 FIFO：bob 在前");

            // bob 减量（同价）：失去时间优先，排到 carol 后面
            s.amendOrder(bobId, "tp-amend-1", 9900, 60);
            List<Order> bids1 = s.orderBook(BTC).bidsSnapshot();
            assertEquals(List.of(carolId, bobId),
                    bids1.stream().map(Order::getOrderId).toList(),
                    "判定依据：改单后换序号排队尾，carol 在前");

            // alice 卖 120 @9900：先成交 carol 100，再成交 bob 20
            s.submitOrder("tp-sell-alice", "alice", BTC, "SELL", "LIMIT", 9900, 120);
            assertEquals(100, s.getOrder(carolId).getFilledQty(), "队首 carol 先全成交");
            assertEquals(20, s.getOrder(bobId).getFilledQty(), "队尾 bob 只成交剩余 20");
            assertEquals(40, s.getOrder(bobId).remainingQty());

            log.info("[amendPriority] bobOrder={} carolOrder={} 判定=改单排队尾、先carol后bob bob={} carol={}",
                    bobId, carolId, s.snapshot("bob"), s.snapshot("carol"));
        }
    }

    @Test
    @DisplayName("改价进入盘口：立即按现有撮合规则以 maker 价成交")
    void amendTriggersImmediateMatch(@TempDir Path dir) {
        try (Harness h = start(dir)) {
            TradingService s = h.service();
            s.setReferencePrice(BTC, 10000);

            // alice 卖 100 @10000 挂盘；bob 买 9900 不交叉，挂着
            s.submitOrder("im-sell", "alice", BTC, "SELL", "LIMIT", 10000, 100);
            OrderResult buy = s.submitOrder("im-buy", "bob", BTC, "BUY", "LIMIT", 9900, 60);
            long buyId = buy.order().getOrderId();
            assertEquals(0, buy.order().getFilledQty());
            assertEquals(1, s.orderBook(BTC).bidsSnapshot().size());

            AccountSnapshot bobBefore = s.snapshot("bob");
            AccountSnapshot aliceBefore = s.snapshot("alice");

            // bob 改价 9900 -> 10000：与卖盘交叉，立即成交 60 @10000（maker 价）
            AmendResult amend = s.amendOrder(buyId, "im-amend-1", 10000, 60);
            assertEquals(1, amend.fillIds().size(), "判定依据：改价交叉后产生一笔成交");
            assertEquals(OrderStatus.FILLED, amend.order().getStatus());
            assertEquals(60, amend.order().getFilledQty());
            assertTrue(s.orderBook(BTC).bidsSnapshot().isEmpty(), "买单全成交，买盘清空");

            // 结算：bob 冻结按旧限价 9900 释放、按新限价 10000 冻结后再按成交价结算，
            // 净效果 = 现金减少 60*10000/10000=60，持仓 +60
            AccountSnapshot bobAfter = s.snapshot("bob");
            AccountSnapshot aliceAfter = s.snapshot("alice");
            assertEquals(bobBefore.cash() - 60, bobAfter.cash());
            assertEquals(bobBefore.cash() - 60, bobAfter.availableCash(),
                    "成交后无剩余冻结，可用=总额");
            assertEquals(60, bobAfter.positions().get(BTC)[0]);
            assertEquals(aliceBefore.cash() + 60, aliceAfter.cash());
            assertEquals(aliceBefore.positions().get(BTC)[0] - 60,
                    aliceAfter.positions().get(BTC)[0]);

            log.info("[amendMatch] buyOrder={} fillIds={} 判定=改价交叉即时成交@maker价 bob={} alice={}",
                    buyId, amend.fillIds(), bobAfter, aliceAfter);
        }
    }

    @Test
    @DisplayName("改单拒绝原因可区分，且拒绝后账户与订单零变化")
    void amendRejectReasonsAreDistinguishable(@TempDir Path dir) {
        try (Harness h = start(dir)) {
            TradingService s = h.service();
            s.setReferencePrice(BTC, 10000);

            // ORDER_NOT_FOUND
            TradingException notFound = assertThrows(TradingException.class,
                    () -> s.amendOrder(999_999L, "na-1", 10000, 10));
            assertEquals(RejectReason.ORDER_NOT_FOUND, notFound.getReason());

            // 准备：alice 卖 100 @10000；bob 买 100 @10000 全成交；bob 另挂 9900 买 50
            s.submitOrder("rj-sell", "alice", BTC, "SELL", "LIMIT", 10000, 100);
            OrderResult filled = s.submitOrder("rj-buy-filled", "bob", BTC, "BUY", "LIMIT", 10000, 100);
            OrderResult resting = s.submitOrder("rj-buy-rest", "bob", BTC, "BUY", "LIMIT", 9900, 50);
            long filledId = filled.order().getOrderId();
            long restId = resting.order().getOrderId();

            // ORDER_ALREADY_FILLED
            TradingException alreadyFilled = assertThrows(TradingException.class,
                    () -> s.amendOrder(filledId, "na-2", 10000, 10));
            assertEquals(RejectReason.ORDER_ALREADY_FILLED, alreadyFilled.getReason());

            // ORDER_ALREADY_CANCELED
            s.cancel(restId);
            TradingException alreadyCanceled = assertThrows(TradingException.class,
                    () -> s.amendOrder(restId, "na-3", 10000, 10));
            assertEquals(RejectReason.ORDER_ALREADY_CANCELED, alreadyCanceled.getReason());

            // ORDER_NOT_AMENDABLE：市价单不挂盘，不可改
            s.submitOrder("rj-sell-2", "alice", BTC, "SELL", "LIMIT", 10000, 50);
            OrderResult mkt = s.submitOrder("rj-mkt", "bob", BTC, "BUY", "MARKET", 0, 50);
            assertEquals(OrderStatus.FILLED, mkt.order().getStatus());
            TradingException notAmendable = assertThrows(TradingException.class,
                    () -> s.amendOrder(mkt.order().getOrderId(), "na-4", 10000, 10));
            assertEquals(RejectReason.ORDER_NOT_AMENDABLE, notAmendable.getReason());

            // INVALID_QUANTITY：部分成交后改量 <= filledQty
            s.submitOrder("rj-sell-3", "alice", BTC, "SELL", "LIMIT", 10000, 100);
            OrderResult partial = s.submitOrder("rj-buy-partial", "carol", BTC, "BUY", "LIMIT", 10000, 100);
            // carol 全成交，再造一个部分成交：alice 卖 100，carol 买 60 后剩 40 的是 alice；
            // 改用 carol 挂买 200 @9900 再让 alice 卖 60 部分成交它
            OrderResult big = s.submitOrder("rj-buy-big", "carol", BTC, "BUY", "LIMIT", 9800, 200);
            s.submitOrder("rj-sell-4", "alice", BTC, "SELL", "LIMIT", 9800, 60);
            assertEquals(60, s.getOrder(big.order().getOrderId()).getFilledQty());
            TradingException badQty = assertThrows(TradingException.class,
                    () -> s.amendOrder(big.order().getOrderId(), "na-5", 9800, 60));
            assertEquals(RejectReason.INVALID_QUANTITY, badQty.getReason(),
                    "判定依据：改后总量必须大于已成交量");

            // INVALID_PRICE：价格 <= 0
            TradingException badPrice = assertThrows(TradingException.class,
                    () -> s.amendOrder(big.order().getOrderId(), "na-6", 0, 300));
            assertEquals(RejectReason.INVALID_PRICE, badPrice.getReason());

            // PRICE_OUT_OF_BAND：偏离参考价超过 5%
            TradingException outOfBand = assertThrows(TradingException.class,
                    () -> s.amendOrder(big.order().getOrderId(), "na-7", 20000, 300));
            assertEquals(RejectReason.PRICE_OUT_OF_BAND, outOfBand.getReason());

            // QUANTITY_EXCEEDS_LIMIT：改后剩余量超单笔上限
            TradingException tooMany = assertThrows(TradingException.class,
                    () -> s.amendOrder(big.order().getOrderId(), "na-8", 9800, 2_000_000));
            assertEquals(RejectReason.QUANTITY_EXCEEDS_LIMIT, tooMany.getReason());

            // INSUFFICIENT_AVAILABLE（买）：erin 资金 5000，买 4000@10000 冻结 4000，
            // 改量到 6000 需要 6000 > 5000
            OrderResult erinBuy = s.submitOrder("rj-erin-buy", "erin", BTC, "BUY", "LIMIT", 10000, 4000);
            TradingException noCash = assertThrows(TradingException.class,
                    () -> s.amendOrder(erinBuy.order().getOrderId(), "na-9", 10000, 6000));
            assertEquals(RejectReason.INSUFFICIENT_AVAILABLE, noCash.getReason());

            // INSUFFICIENT_AVAILABLE（卖）：frank 持仓 5000，卖 4000 @10100（不与簿内买盘交叉），
            // 改量到 6000 超可卖持仓
            OrderResult frankSell = s.submitOrder("rj-frank-sell", "frank", BTC, "SELL", "LIMIT", 10100, 4000);
            assertEquals(OrderStatus.NEW, frankSell.order().getStatus());
            TradingException noPos = assertThrows(TradingException.class,
                    () -> s.amendOrder(frankSell.order().getOrderId(), "na-10", 10000, 6000));
            assertEquals(RejectReason.INSUFFICIENT_AVAILABLE, noPos.getReason());

            // 所有拒绝后：订单与账户保持拒绝前状态
            Order bigOrder = s.getOrder(big.order().getOrderId());
            assertEquals(9800, bigOrder.getLimitPrice(), "拒绝后价格不变");
            assertEquals(200, bigOrder.getQuantity(), "拒绝后数量不变");
            assertEquals(60, bigOrder.getFilledQty());
            assertEquals(OrderStatus.NEW, bigOrder.getStatus());
            assertEquals(1000, s.snapshot("erin").availableCash(),
                    "判定依据：erin 拒绝后冻结保持 4000，可用 1000");
            assertEquals(1000, s.snapshot("frank").positions().get(BTC)[1],
                    "判定依据：frank 拒绝后冻结保持 4000，可卖 1000");

            log.info("[amendReject] 判定: notFound={} filled={} canceled={} market={} qty={} price={} band={} limit={} cash={} pos={}",
                    notFound.getReason(), alreadyFilled.getReason(), alreadyCanceled.getReason(),
                    notAmendable.getReason(), badQty.getReason(), badPrice.getReason(),
                    outOfBand.getReason(), tooMany.getReason(), noCash.getReason(), noPos.getReason());
            log.info("[amendReject] 拒绝后快照 erin={} frank={} carol={}",
                    s.snapshot("erin"), s.snapshot("frank"), s.snapshot("carol"));
        }
    }

    @Test
    @DisplayName("重复改单幂等：同一 clientAmendId 重试只生效一次，含触发成交的改单")
    void duplicateAmendIsIdempotent(@TempDir Path dir) {
        try (Harness h = start(dir)) {
            TradingService s = h.service();
            s.setReferencePrice(BTC, 10000);

            // 普通改单重试
            OrderResult buy = s.submitOrder("idem-buy", "bob", BTC, "BUY", "LIMIT", 10000, 100);
            long buyId = buy.order().getOrderId();
            AmendResult first = s.amendOrder(buyId, "idem-a1", 9900, 100);
            assertFalse(first.idempotent());
            assertEquals(9900, first.order().getLimitPrice());
            AccountSnapshot bobAfterFirst = s.snapshot("bob");
            assertEquals(10_000_000_000L - 99, bobAfterFirst.availableCash(),
                    "冻结=100*9900/10000=99");

            AmendResult retry = s.amendOrder(buyId, "idem-a1", 9900, 100);
            assertTrue(retry.idempotent(), "判定依据：相同 clientAmendId 命中幂等");
            assertTrue(retry.fillIds().isEmpty(), "幂等命中不得附带新成交");
            assertEquals(bobAfterFirst, s.snapshot("bob"), "重复改单不得重复调整冻结");
            assertEquals(9900, s.getOrder(buyId).getLimitPrice());

            // 换 clientAmendId 可再次改单
            AmendResult second = s.amendOrder(buyId, "idem-a2", 9800, 100);
            assertFalse(second.idempotent());
            assertEquals(9800, s.getOrder(buyId).getLimitPrice());

            // 触发即时成交的改单重试：成交不得重复结算
            s.submitOrder("idem-sell", "alice", BTC, "SELL", "LIMIT", 10000, 50);
            OrderResult buy2 = s.submitOrder("idem-buy-2", "carol", BTC, "BUY", "LIMIT", 9800, 50);
            long buy2Id = buy2.order().getOrderId();
            AmendResult matching = s.amendOrder(buy2Id, "idem-a3", 10000, 50);
            assertEquals(1, matching.fillIds().size());
            assertEquals(OrderStatus.FILLED, matching.order().getStatus());
            AccountSnapshot carolAfterMatch = s.snapshot("carol");
            AccountSnapshot aliceAfterMatch = s.snapshot("alice");

            AmendResult matchRetry = s.amendOrder(buy2Id, "idem-a3", 10000, 50);
            assertTrue(matchRetry.idempotent(), "已成交的改单重试仍命中幂等（先于状态检查）");
            assertTrue(matchRetry.fillIds().isEmpty());
            assertEquals(carolAfterMatch, s.snapshot("carol"), "成交不得重复结算");
            assertEquals(aliceAfterMatch, s.snapshot("alice"), "成交不得重复结算");

            log.info("[amendIdempotent] buyOrder={} buyOrder2={} 判定=clientAmendId幂等 bob={} carol={} alice={}",
                    buyId, buy2Id, s.snapshot("bob"), s.snapshot("carol"), s.snapshot("alice"));
        }
    }

    @Test
    @DisplayName("并发改单/撤单/同键重试竞争：最终状态自洽，冻结不重复调整")
    void concurrentAmendCancelAndDuplicate(@TempDir Path dir) throws Exception {
        try (Harness h = start(dir)) {
            TradingService s = h.service();
            s.setReferencePrice(BTC, 10000);

            // A. 10 线程改单（不同 clientAmendId）+ 1 线程撤单 竞争同一订单
            OrderResult buy = s.submitOrder("cc-buy", "bob", BTC, "BUY", "LIMIT", 10000, 1000);
            long orderId = buy.order().getOrderId();
            int amendThreads = 10;
            ExecutorService pool = Executors.newFixedThreadPool(amendThreads + 1);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger amendOk = new AtomicInteger();
            AtomicInteger amendCanceled = new AtomicInteger();
            AtomicInteger unexpected = new AtomicInteger();
            for (int t = 0; t < amendThreads; t++) {
                final int idx = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        s.amendOrder(orderId, "cc-amend-" + idx, 10000, 900);
                        amendOk.incrementAndGet();
                    } catch (TradingException ex) {
                        if (ex.getReason() == RejectReason.ORDER_ALREADY_CANCELED) {
                            amendCanceled.incrementAndGet();
                        } else {
                            unexpected.incrementAndGet();
                        }
                    } catch (Exception ex) {
                        unexpected.incrementAndGet();
                    }
                });
            }
            pool.submit(() -> {
                try {
                    start.await();
                    s.cancel(orderId);
                } catch (TradingException ex) {
                    unexpected.incrementAndGet();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

            assertEquals(0, unexpected.get(), "判定依据：只允许成功或 ALREADY_CANCELED 两种结局");
            assertEquals(amendThreads, amendOk.get() + amendCanceled.get(),
                    "每个改单请求都有确定结论");
            assertEquals(OrderStatus.CANCELED, s.getOrder(orderId).getStatus(),
                    "撤单最终生效，订单不可能既被改又继续挂盘");
            assertEquals(10_000_000_000L, s.snapshot("bob").availableCash(),
                    "判定依据：撤单后冻结全部释放，无重复冻结/扣减");
            log.info("[amendCancelRace] orderId={} amendOk={} amendCanceled={} 判定=串行化后状态自洽 bob={}",
                    orderId, amendOk.get(), amendCanceled.get(), s.snapshot("bob"));

            // B. 20 线程用同一 clientAmendId 改同一单：只生效一次
            OrderResult buy2 = s.submitOrder("cc-buy-2", "bob", BTC, "BUY", "LIMIT", 10000, 500);
            long orderId2 = buy2.order().getOrderId();
            int dupThreads = 20;
            ExecutorService pool2 = Executors.newFixedThreadPool(dupThreads);
            CountDownLatch start2 = new CountDownLatch(1);
            AtomicInteger applied = new AtomicInteger();
            AtomicInteger idempotentHits = new AtomicInteger();
            for (int t = 0; t < dupThreads; t++) {
                pool2.submit(() -> {
                    try {
                        start2.await();
                        AmendResult r = s.amendOrder(orderId2, "cc-same-amend", 10000, 400);
                        if (r.idempotent()) {
                            idempotentHits.incrementAndGet();
                        } else {
                            applied.incrementAndGet();
                        }
                    } catch (Exception ex) {
                        unexpected.incrementAndGet();
                    }
                });
            }
            start2.countDown();
            pool2.shutdown();
            assertTrue(pool2.awaitTermination(30, TimeUnit.SECONDS));

            assertEquals(1, applied.get(), "判定依据：同一 clientAmendId 只生效一次");
            assertEquals(dupThreads - 1, idempotentHits.get());
            assertEquals(400, s.getOrder(orderId2).getQuantity());
            assertEquals(10_000_000_000L - 400, s.snapshot("bob").availableCash(),
                    "冻结只按改后 400 调整一次，不重复扣减");
            assertEquals(0, unexpected.get());
            log.info("[amendDupRace] orderId={} threads={} applied={} idempotent={} 判定=并发同键幂等 bob={}",
                    orderId2, dupThreads, applied.get(), idempotentHits.get(), s.snapshot("bob"));
        }
    }

    @Test
    @DisplayName("改单+成交+撤单混合后重启重放：订单/订单簿排队/账户四栏逐字段一致，幂等表恢复")
    void amendReplayConsistency(@TempDir Path dir) {
        long aliceSellId;
        long bobBuyId;
        long carolBuyId;
        long canceledId;
        AccountSnapshot aliceBefore;
        AccountSnapshot bobBefore;
        AccountSnapshot carolBefore;
        List<Long> bidsBefore;
        List<Long> asksBefore;
        Map<Long, List<Object>> orderStatesBefore = new HashMap<>();

        try (Harness h = start(dir)) {
            TradingService s = h.service();
            s.setReferencePrice(BTC, 10000);

            // alice 卖 300 @10000；bob 买 100 成交，alice 剩 200
            OrderResult sell = s.submitOrder("rp-sell-1", "alice", BTC, "SELL", "LIMIT", 10000, 300);
            aliceSellId = sell.order().getOrderId();
            s.submitOrder("rp-buy-1", "bob", BTC, "BUY", "LIMIT", 10000, 100);

            // alice 部分成交后改单：改价 10100、改量 250（剩余 150）
            s.amendOrder(aliceSellId, "rp-amend-1", 10100, 250);
            // carol 买 50 @10100 与 alice 成交，alice filled=150 剩 100
            s.submitOrder("rp-buy-2", "carol", BTC, "BUY", "LIMIT", 10100, 50);

            // bob、carol 同价 9900 挂单；bob 改单（同价减量）后应排到 carol 后面
            OrderResult bobRest = s.submitOrder("rp-buy-3", "bob", BTC, "BUY", "LIMIT", 9900, 80);
            bobBuyId = bobRest.order().getOrderId();
            OrderResult carolRest = s.submitOrder("rp-buy-4", "carol", BTC, "BUY", "LIMIT", 9900, 30);
            carolBuyId = carolRest.order().getOrderId();
            s.amendOrder(bobBuyId, "rp-amend-2", 9900, 70);

            // 再挂一单然后撤掉，覆盖撤单与改单混合的流水
            OrderResult toCancel = s.submitOrder("rp-sell-2", "alice", BTC, "SELL", "LIMIT", 10200, 50);
            canceledId = toCancel.order().getOrderId();
            s.cancel(canceledId);

            aliceBefore = s.snapshot("alice");
            bobBefore = s.snapshot("bob");
            carolBefore = s.snapshot("carol");
            bidsBefore = s.orderBook(BTC).bidsSnapshot().stream().map(Order::getOrderId).toList();
            asksBefore = s.orderBook(BTC).asksSnapshot().stream().map(Order::getOrderId).toList();
            assertEquals(List.of(carolBuyId, bobBuyId), bidsBefore, "改单后 bob 排队尾");
            for (long id : List.of(aliceSellId, bobBuyId, carolBuyId, canceledId)) {
                orderStatesBefore.put(id, orderState(s.getOrder(id)));
            }
            log.info("[amendReplay][beforeRestart] bids={} asks={} alice={} bob={} carol={}",
                    bidsBefore, asksBefore, aliceBefore, bobBefore, carolBefore);
        }

        // 模拟重启：同一事件目录全新上下文重放
        try (Harness h2 = start(dir)) {
            TradingService s2 = h2.service();

            assertEquals(aliceBefore, s2.snapshot("alice"), "重放后 alice 四栏一致");
            assertEquals(bobBefore, s2.snapshot("bob"), "重放后 bob 四栏一致");
            assertEquals(carolBefore, s2.snapshot("carol"), "重放后 carol 四栏一致");

            List<Long> bidsAfter = s2.orderBook(BTC).bidsSnapshot().stream()
                    .map(Order::getOrderId).toList();
            List<Long> asksAfter = s2.orderBook(BTC).asksSnapshot().stream()
                    .map(Order::getOrderId).toList();
            assertEquals(bidsBefore, bidsAfter, "判定依据：重放后买盘各档位排队顺序一致");
            assertEquals(asksBefore, asksAfter, "判定依据：重放后卖盘各档位排队顺序一致");

            for (Map.Entry<Long, List<Object>> e : orderStatesBefore.entrySet()) {
                assertEquals(e.getValue(), orderState(s2.getOrder(e.getKey())),
                        "订单 " + e.getKey() + " 价格/数量/成交/状态/序号逐字段一致");
            }

            // 改单幂等表随重放恢复：重复 clientAmendId 仍命中，不再改单
            AmendResult dupAmend = s2.amendOrder(bobBuyId, "rp-amend-2", 9900, 70);
            assertTrue(dupAmend.idempotent(), "重放后改单幂等表必须恢复");
            assertEquals(bobBefore, s2.snapshot("bob"), "重放后重复改单不得改变账户");
            // 下单幂等表同样恢复
            assertTrue(s2.submitOrder("rp-buy-3", "bob", BTC, "BUY", "LIMIT", 9900, 80)
                    .idempotent());
            assertEquals(bobBefore, s2.snapshot("bob"));

            log.info("[amendReplay][afterRestart] 判定=可见状态与重启前逐字段一致 bids={} asks={} alice={} bob={} carol={}",
                    bidsAfter, asksAfter, s2.snapshot("alice"), s2.snapshot("bob"), s2.snapshot("carol"));
        }
    }

    /** 订单关键字段快照：价格/数量/成交/状态/接收序号。 */
    private static List<Object> orderState(Order o) {
        return List.of(o.getLimitPrice(), o.getQuantity(), o.getFilledQty(),
                o.getStatus(), o.getSeq());
    }
}
