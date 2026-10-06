package com.github.h3yow.ordermatchingenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.h3yow.ordermatchingenginejava.domain.Order;
import com.github.h3yow.ordermatchingenginejava.domain.OrderStatus;
import com.github.h3yow.ordermatchingenginejava.domain.RejectReason;
import com.github.h3yow.ordermatchingenginejava.domain.TradingException;
import com.github.h3yow.ordermatchingenginejava.engine.OrderBook;
import com.github.h3yow.ordermatchingenginejava.service.AccountSnapshot;
import com.github.h3yow.ordermatchingenginejava.service.OrderResult;
import com.github.h3yow.ordermatchingenginejava.service.TradingService;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
 * 改单（amend）测试：成功路径（改价/改量/同时改+排队位置）、各类可区分拒绝、
 * 幂等、即时成交、并发竞争、重启重放一致性。
 */
class AmendOrderTest {

    private static final Logger log = LoggerFactory.getLogger(AmendOrderTest.class);

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
                        "trading.seed-accounts.carol=1000",
                        "trading.seed-positions.alice__BTC=1000000",
                        "trading.max-order-quantity=1000000",
                        "trading.max-deviation-bps=500")
                .run();
        return new Harness(ctx, ctx.getBean(TradingService.class));
    }

    private long pos(TradingService s, String acc, String symbol) {
        return s.snapshot(acc).positions().getOrDefault(symbol, new long[2])[0];
    }

    private long availPos(TradingService s, String acc, String symbol) {
        return s.snapshot(acc).positions().getOrDefault(symbol, new long[2])[1];
    }

    @Test
    @DisplayName("改价：冻结重算且失去原时间优先，排到同价位队尾")
    void amendPriceRecomputesFreezeAndLosesTimePriority(@TempDir Path dir) {
        try (Harness h = start(dir)) {
            TradingService s = h.service();
            // 不设参考价：无价格带限制，便于纯冻结核对
            AccountSnapshot bob0 = s.snapshot("bob");

            // alice 先挂两笔同价卖单，形成队列 [A1, A2]
            OrderResult a1 = s.submitOrder("a1", "alice", "BTC", "SELL", "LIMIT", 10000, 100);
            OrderResult a2 = s.submitOrder("a2", "alice", "BTC", "SELL", "LIMIT", 10000, 100);
            long a1Id = a1.order().getOrderId();
            long a2Id = a2.order().getOrderId();
            long seqA1 = a1.order().getSeq();

            // bob 买单 200@9000 冻结 180（=200*9000/10000）
            OrderResult b1 = s.submitOrder("b1", "bob", "BTC", "BUY", "LIMIT", 9000, 200);
            long b1Id = b1.order().getOrderId();
            long frozenAt9000 = 200 * 9000 / 10000;
            assertEquals(bob0.availableCash() - frozenAt9000, s.snapshot("bob").availableCash(),
                    "判定依据：买单冻结 = 数量*限价");

            // 改价 9000 -> 9500（仍不与 10000 卖盘交叉）：冻结从 180 重算为 190
            OrderResult amended = s.amendOrder(b1Id, "amend-b1-price", 9500L, null);
            assertFalse(amended.idempotent());
            assertEquals(9500, amended.order().getLimitPrice());
            assertEquals(OrderStatus.NEW, amended.order().getStatus());
            long frozenAt9500 = 200 * 9500 / 10000;
            assertEquals(bob0.availableCash() - frozenAt9500, s.snapshot("bob").availableCash(),
                    "判定依据：改价后冻结按新价重算，差额从可用余额扣除");
            assertTrue(amended.order().getSeq() > seqA1, "改单必须刷新 seq（失去原时间优先）");

            // alice 改 A1 数量（不改价）：A1 应排到 A2 后面
            OrderResult a1Amended = s.amendOrder(a1Id, "amend-a1-qty", null, 150L);
            assertEquals(150, a1Amended.order().getQuantity());
            List<Order> asks = s.orderBook("BTC").asksSnapshot();
            assertEquals(2, asks.size());
            assertEquals(a2Id, asks.get(0).getOrderId(), "判定依据：未改的 A2 排在队首");
            assertEquals(a1Id, asks.get(1).getOrderId(), "判定依据：改过的 A1 失去时间优先排到队尾");

            // alice 卖单冻结：A1 改量 100->150 后可卖持仓同步多冻结 50
            assertEquals(1_000_000 - 150 - 100, availPos(s, "alice", "BTC"),
                    "判定依据：改量后卖单冻结 = 新剩余数量之和");

            log.info("[amendPrice] b1Id={} a1Id={} a2Id={} 判定=改价冻结重算+改单丢队首 bob={} alice={}",
                    b1Id, a1Id, a2Id, s.snapshot("bob"), s.snapshot("alice"));
        }
    }

    @Test
    @DisplayName("减量/加量：冻结跟随剩余数量重算，部分成交后改量以总数量约定")
    void amendQuantityAdjustsFreeze(@TempDir Path dir) {
        try (Harness h = start(dir)) {
            TradingService s = h.service();
            AccountSnapshot bob0 = s.snapshot("bob");

            // bob 买 200@10000（无参考价，无对手盘，纯挂单）
            OrderResult b1 = s.submitOrder("q1", "bob", "BTC", "BUY", "LIMIT", 10000, 200);
            long b1Id = b1.order().getOrderId();
            assertEquals(bob0.availableCash() - 200, s.snapshot("bob").availableCash());

            // 减量 200 -> 120：释放 80 的冻结
            OrderResult reduced = s.amendOrder(b1Id, "amend-q-dec", null, 120L);
            assertEquals(120, reduced.order().getQuantity());
            assertEquals(bob0.availableCash() - 120, s.snapshot("bob").availableCash(),
                    "判定依据：减量释放差额冻结");

            // 加量 120 -> 300：重新冻结 300
            OrderResult increased = s.amendOrder(b1Id, "amend-q-inc", null, 300L);
            assertEquals(300, increased.order().getQuantity());
            assertEquals(bob0.availableCash() - 300, s.snapshot("bob").availableCash(),
                    "判定依据：加量按新总量冻结差额");

            // 部分成交场景：alice 卖 50@10000 吃掉 bob 一部分，bob 已成交 50
            s.submitOrder("q-ask", "alice", "BTC", "SELL", "LIMIT", 10000, 50);
            Order partially = s.getOrder(b1Id);
            assertEquals(50, partially.getFilledQty());
            assertEquals(250, partially.remainingQty());

            // 部分成交后改总量 300 -> 180：新剩余 = 180-50 = 130，冻结随之重算
            AccountSnapshot beforeAmend = s.snapshot("bob");
            OrderResult afterFill = s.amendOrder(b1Id, "amend-q-partial", null, 180L);
            assertEquals(180, afterFill.order().getQuantity());
            assertEquals(50, afterFill.order().getFilledQty(), "已成交部分不受改单影响");
            assertEquals(130, afterFill.order().remainingQty());
            long expectedAvail = beforeAmend.availableCash() + (250 - 130) * 10000 / 10000;
            assertEquals(expectedAvail, s.snapshot("bob").availableCash(),
                    "判定依据：部分成交后改量按新剩余重算冻结");

            log.info("[amendQty] b1Id={} 判定=减/加量冻结重算+部分成交改量 bob={}",
                    b1Id, s.snapshot("bob"));
        }
    }

    @Test
    @DisplayName("可区分拒绝：不存在/已成交/已撤销/市价单/数量非法/超限额/余额不足")
    void distinguishableRejections(@TempDir Path dir) {
        try (Harness h = start(dir)) {
            TradingService s = h.service();
            s.setReferencePrice("BTC", 10000);

            // 不存在的订单
            TradingException notFound = assertThrows(TradingException.class,
                    () -> s.amendOrder(999_999L, "amend-nf", 10000L, null));
            assertEquals(RejectReason.ORDER_NOT_FOUND, notFound.getReason());

            // 已完全成交：alice 卖 100@10000，bob 买 100@10000 全部成交
            s.submitOrder("r-ask", "alice", "BTC", "SELL", "LIMIT", 10000, 100);
            OrderResult filledBuy = s.submitOrder("r-buy", "bob", "BTC", "BUY", "LIMIT", 10000, 100);
            assertEquals(OrderStatus.FILLED, filledBuy.order().getStatus());
            TradingException alreadyFilled = assertThrows(TradingException.class,
                    () -> s.amendOrder(filledBuy.order().getOrderId(), "amend-filled", 10000L, null));
            assertEquals(RejectReason.ORDER_ALREADY_FILLED, alreadyFilled.getReason());

            // 已撤销：bob 挂 9800 买单后撤掉（9800 在参考价 10000 的 5% 带内）
            OrderResult resting = s.submitOrder("r-rest", "bob", "BTC", "BUY", "LIMIT", 9800, 100);
            long restingId = resting.order().getOrderId();
            s.cancel(restingId);
            TradingException alreadyCanceled = assertThrows(TradingException.class,
                    () -> s.amendOrder(restingId, "amend-canceled", 9900L, null));
            assertEquals(RejectReason.ORDER_ALREADY_CANCELED, alreadyCanceled.getReason());

            // 市价单：不挂盘。alice 先挂 5@10000 卖单，bob 市价买 10 成交 5、剩余 5 被撤销，
            // 对这样的市价单改单必须得到可区分的拒绝原因而非笼统错误
            s.submitOrder("r-ask-mkt", "alice", "BTC", "SELL", "LIMIT", 10000, 5);
            OrderResult mkt = s.submitOrder("r-mkt", "bob", "BTC", "BUY", "MARKET", 0, 10);
            long mktId = mkt.order().getOrderId();
            assertEquals(OrderStatus.CANCELED, mkt.order().getStatus());
            TradingException mktAmend = assertThrows(TradingException.class,
                    () -> s.amendOrder(mktId, "amend-mkt", 10000L, null));
            assertTrue(mktAmend.getReason() == RejectReason.ORDER_NOT_AMENDABLE
                            || mktAmend.getReason() == RejectReason.ORDER_ALREADY_CANCELED,
                    "判定依据：市价单改单必须被拒绝且原因可区分，实际=" + mktAmend.getReason());

            // 新数量 <= 已成交量：bob 买 100@9000 挂单，先被 alice 成交 60，再改总量到 50
            OrderResult b2 = s.submitOrder("r-b2", "bob", "BTC", "BUY", "LIMIT", 10000, 100);
            long b2Id = b2.order().getOrderId();
            s.submitOrder("r-ask2", "alice", "BTC", "SELL", "LIMIT", 10000, 60);
            assertEquals(60, s.getOrder(b2Id).getFilledQty());
            TradingException badQty = assertThrows(TradingException.class,
                    () -> s.amendOrder(b2Id, "amend-badqty", null, 50L));
            assertEquals(RejectReason.INVALID_QUANTITY, badQty.getReason());

            // 超单笔限额
            TradingException tooBig = assertThrows(TradingException.class,
                    () -> s.amendOrder(b2Id, "amend-toobig", null, 2_000_000L));
            assertEquals(RejectReason.QUANTITY_EXCEEDS_LIMIT, tooBig.getReason());

            // 价格偏离参考价
            TradingException outOfBand = assertThrows(TradingException.class,
                    () -> s.amendOrder(b2Id, "amend-band", 20000L, null));
            assertEquals(RejectReason.PRICE_OUT_OF_BAND, outOfBand.getReason());

            // 余额不足：carol 只有 1000 现金，挂 1@10000（冻结 1）后改量
            OrderResult c1 = s.submitOrder("r-c1", "carol", "BTC", "BUY", "LIMIT", 10000, 1);
            TradingException tooBig2 = assertThrows(TradingException.class,
                    () -> s.amendOrder(c1.order().getOrderId(), "amend-toobig2", null, 2_000_000L));
            assertEquals(RejectReason.QUANTITY_EXCEEDS_LIMIT, tooBig2.getReason());
            TradingException noCash = assertThrows(TradingException.class,
                    () -> s.amendOrder(c1.order().getOrderId(), "amend-nocash", null, 900_000L));
            assertEquals(RejectReason.INSUFFICIENT_AVAILABLE, noCash.getReason());

            // 所有拒绝都不得留下副作用：carol 冻结仍是 1*10000/10000=1
            assertEquals(1000 - 1, s.snapshot("carol").availableCash(),
                    "判定依据：被拒绝的改单不得改变账户冻结");
            assertEquals(1, s.getOrder(c1.order().getOrderId()).getQuantity(),
                    "判定依据：被拒绝的改单不得改变订单");

            log.info("[amendReject] 判定=NOT_FOUND/ALREADY_FILLED/ALREADY_CANCELED/市价单/"
                            + "INVALID_QUANTITY/QUANTITY_EXCEEDS_LIMIT/PRICE_OUT_OF_BAND/"
                            + "INSUFFICIENT_AVAILABLE 均可区分 carol={}",
                    s.snapshot("carol"));
        }
    }

    @Test
    @DisplayName("幂等：同一 clientAmendId 重复改单只生效一次，不重复调整冻结")
    void duplicateAmendIsIdempotent(@TempDir Path dir) {
        try (Harness h = start(dir)) {
            TradingService s = h.service();
            AccountSnapshot bob0 = s.snapshot("bob");

            OrderResult b1 = s.submitOrder("i-b1", "bob", "BTC", "BUY", "LIMIT", 10000, 100);
            long b1Id = b1.order().getOrderId();

            OrderResult first = s.amendOrder(b1Id, "amend-idem-1", 12000L, 200L);
            assertFalse(first.idempotent());
            assertEquals(12000, first.order().getLimitPrice());
            assertEquals(200, first.order().getQuantity());
            AccountSnapshot afterFirst = s.snapshot("bob");
            assertEquals(bob0.availableCash() - 200 * 12000 / 10000, afterFirst.availableCash());
            long seqAfterFirst = first.order().getSeq();

            // 网络重试：完全相同的改单请求再提交两次
            OrderResult dup1 = s.amendOrder(b1Id, "amend-idem-1", 12000L, 200L);
            OrderResult dup2 = s.amendOrder(b1Id, "amend-idem-1", 12000L, 200L);
            assertTrue(dup1.idempotent(), "判定依据：同 orderId+clientAmendId 命中幂等");
            assertTrue(dup2.idempotent());
            assertTrue(dup1.fillIds().isEmpty(), "幂等命中不得伪报新成交");
            assertEquals(afterFirst, s.snapshot("bob"),
                    "判定依据：重复改单不得重复调整冻结");
            assertEquals(seqAfterFirst, s.getOrder(b1Id).getSeq(),
                    "判定依据：重复改单不得再次刷新排队序号");
            assertEquals(200, s.getOrder(b1Id).getQuantity());

            // 不同 clientAmendId 是新的改单请求，正常生效
            OrderResult second = s.amendOrder(b1Id, "amend-idem-2", null, 150L);
            assertFalse(second.idempotent());
            assertEquals(150, second.order().getQuantity());
            assertEquals(bob0.availableCash() - 150 * 12000 / 10000,
                    s.snapshot("bob").availableCash());

            log.info("[amendIdempotent] b1Id={} 判定=同 clientAmendId 只生效一次 bob={}",
                    b1Id, s.snapshot("bob"));
        }
    }

    @Test
    @DisplayName("改单触发即时成交：改价交叉盘口立即按 maker 价成交，现金/持仓同步结算")
    void amendCrossingPriceFillsImmediately(@TempDir Path dir) {
        try (Harness h = start(dir)) {
            TradingService s = h.service();
            s.setReferencePrice("BTC", 10000);

            // alice 卖 100@10000 挂簿；bob 买 60@9800 挂簿（不交叉）
            s.submitOrder("x-ask", "alice", "BTC", "SELL", "LIMIT", 10000, 100);
            OrderResult buy = s.submitOrder("x-buy", "bob", "BTC", "BUY", "LIMIT", 9800, 60);
            long buyId = buy.order().getOrderId();
            assertEquals(0, buy.order().getFilledQty());
            AccountSnapshot alice0 = s.snapshot("alice");
            AccountSnapshot bob0 = s.snapshot("bob");

            // bob 改价 9800 -> 10000：与盘口交叉，立即按 maker 价 10000 成交 60
            OrderResult amended = s.amendOrder(buyId, "amend-cross", 10000L, null);
            assertEquals(60, amended.order().getFilledQty());
            assertEquals(OrderStatus.FILLED, amended.order().getStatus());
            assertEquals(1, amended.fillIds().size(), "判定依据：改单交叉立即产生成交");

            long notional = 10000L * 60 / 10000;
            AccountSnapshot alice1 = s.snapshot("alice");
            AccountSnapshot bob1 = s.snapshot("bob");
            assertEquals(alice0.cash() + notional, alice1.cash(), "卖方现金增加成交额");
            assertEquals(bob0.cash() - notional, bob1.cash(), "买方现金减少成交额");
            // bob 改单前冻结 60*9800/10000=58.8->58(定点)；成交按 10000 扣 60，
            // 差额回补后可用现金 = 改单前可用 + 旧冻结 - 实际成交额
            assertEquals(bob0.availableCash() + 60 * 9800 / 10000 - notional,
                    bob1.availableCash(), "判定依据：旧冻结释放后按实际成交额扣款");
            assertEquals(1_000_000 - 60, pos(s, "alice", "BTC"));
            assertEquals(60, pos(s, "bob", "BTC"));
            assertTrue(s.orderBook("BTC").bidsSnapshot().isEmpty(),
                    "判定依据：改单全部成交后不再挂簿");

            log.info("[amendCross] buyId={} fillIds={} 判定=改价交叉即时成交 alice={} bob={}",
                    buyId, amended.fillIds(), alice1, bob1);
        }
    }

    @Test
    @DisplayName("并发：多线程同 clientAmendId 改同一单，只生效一次；改单与撤单竞争最终自洽")
    void concurrentAmendAndCancelRace(@TempDir Path dir) throws Exception {
        int threads = 16;
        try (Harness h = start(dir)) {
            TradingService s = h.service();
            AccountSnapshot bob0 = s.snapshot("bob");
            OrderResult b1 = s.submitOrder("cc-b1", "bob", "BTC", "BUY", "LIMIT", 10000, 100);
            long b1Id = b1.order().getOrderId();

            // 1) 同 clientAmendId 并发改单：恰好一次真实生效，其余幂等
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger real = new AtomicInteger();
            AtomicInteger idem = new AtomicInteger();
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        OrderResult r = s.amendOrder(b1Id, "cc-amend-same", 12000L, 200L);
                        if (r.idempotent()) {
                            idem.incrementAndGet();
                        } else {
                            real.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
            assertEquals(1, real.get(), "判定依据：同 clientAmendId 并发只能生效一次");
            assertEquals(threads - 1, idem.get());
            assertEquals(bob0.availableCash() - 200L * 12000 / 10000,
                    s.snapshot("bob").availableCash(),
                    "判定依据：冻结只按生效的那一次调整");

            // 2) 改单（各自不同 amendId）与撤单并发：最终要么已改后撤、要么先撤后改被拒
            ExecutorService pool2 = Executors.newFixedThreadPool(threads);
            CountDownLatch start2 = new CountDownLatch(1);
            AtomicInteger amendOk = new AtomicInteger();
            AtomicInteger amendRejected = new AtomicInteger();
            AtomicInteger cancelOk = new AtomicInteger();
            for (int t = 0; t < threads; t++) {
                final int idx = t;
                pool2.submit(() -> {
                    try {
                        start2.await();
                        if (idx == 0) {
                            try {
                                s.cancel(b1Id);
                                cancelOk.incrementAndGet();
                            } catch (TradingException ex) {
                                // 已成交/已撤销均属可解释结果
                            }
                        } else {
                            try {
                                s.amendOrder(b1Id, "cc-amend-" + idx, 11000L, null);
                                amendOk.incrementAndGet();
                            } catch (TradingException ex) {
                                assertTrue(ex.getReason() == RejectReason.ORDER_ALREADY_CANCELED
                                                || ex.getReason() == RejectReason.ORDER_ALREADY_FILLED,
                                        "改单失败原因必须可区分，实际=" + ex.getReason());
                                amendRejected.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start2.countDown();
            pool2.shutdown();
            assertTrue(pool2.awaitTermination(30, TimeUnit.SECONDS));

            Order finalOrder = s.getOrder(b1Id);
            AccountSnapshot bobFinal = s.snapshot("bob");
            assertEquals(1, cancelOk.get(), "撤单只应成功一次");
            assertEquals(OrderStatus.CANCELED, finalOrder.getStatus(),
                    "判定依据：撤单成功后订单最终必为 CANCELED（撤单与改单串行，撤销不可逆）");
            assertEquals(bob0.availableCash(), bobFinal.availableCash(),
                    "判定依据：撤单后全部冻结释放，可用余额回到下单前");
            assertEquals(bob0.cash(), bobFinal.cash(), "无成交时现金总额不变");
            log.info("[amendRace] b1Id={} amendOk={} amendRejected={} cancelOk={} 判定=最终 {} bob={}",
                    b1Id, amendOk.get(), amendRejected.get(), cancelOk.get(),
                    finalOrder.getStatus(), bobFinal);
        }
    }

    @Test
    @DisplayName("并发：多线程改价交叉同一卖盘，恰好一次改单成交，其余得到 ALREADY_FILLED")
    void concurrentCrossingAmendFillsOnce(@TempDir Path dir) throws Exception {
        int threads = 12;
        try (Harness h = start(dir)) {
            TradingService s = h.service();
            s.setReferencePrice("BTC", 10000);
            s.submitOrder("cf-ask", "alice", "BTC", "SELL", "LIMIT", 10000, 100);
            OrderResult buy = s.submitOrder("cf-buy", "bob", "BTC", "BUY", "LIMIT", 9800, 100);
            long buyId = buy.order().getOrderId();

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger amendOk = new AtomicInteger();
            AtomicInteger alreadyFilled = new AtomicInteger();
            for (int t = 0; t < threads; t++) {
                final int idx = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        try {
                            OrderResult r = s.amendOrder(buyId, "cf-amend-" + idx, 10000L, null);
                            assertFalse(r.idempotent());
                            amendOk.incrementAndGet();
                        } catch (TradingException ex) {
                            assertEquals(RejectReason.ORDER_ALREADY_FILLED, ex.getReason(),
                                    "判定依据：被成交后的改单必须是 ALREADY_FILLED");
                            alreadyFilled.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

            assertEquals(1, amendOk.get(), "判定依据：交叉改单恰好一次生效并吃光卖盘");
            assertEquals(threads - 1, alreadyFilled.get());
            assertEquals(OrderStatus.FILLED, s.getOrder(buyId).getStatus());
            assertEquals(100, s.getOrder(buyId).getFilledQty());
            assertEquals(100, pos(s, "bob", "BTC"));
            assertEquals(1_000_000 - 100, pos(s, "alice", "BTC"));
            assertTrue(s.snapshot("bob").availableCash() >= 0);
            log.info("[crossRace] buyId={} 判定=一次改单成交+其余 ALREADY_FILLED bob={} alice={}",
                    buyId, s.snapshot("bob"), s.snapshot("alice"));
        }
    }

    @Test
    @DisplayName("重启重放：改单+部分成交+撤单+市价单混合流水，重启后订单/簿/账户逐字段一致")
    void amendReplayConsistency(@TempDir Path dir) {
        long askId;
        long bidId;
        Map<Long, String> ordersBefore = new LinkedHashMap<>();
        AccountSnapshot aliceBefore;
        AccountSnapshot bobBefore;
        List<Long> bidsBefore;
        List<Long> asksBefore;

        try (Harness h = start(dir)) {
            TradingService s = h.service();
            s.setReferencePrice("BTC", 10000);

            // alice 卖 300@10000；bob 买 100@10000 成交 100，alice 剩 200
            OrderResult ask = s.submitOrder("rp-ask", "alice", "BTC", "SELL", "LIMIT", 10000, 300);
            askId = ask.order().getOrderId();
            s.submitOrder("rp-buy-fill", "bob", "BTC", "BUY", "LIMIT", 10000, 100);

            // bob 挂 50@9800；alice 改单：卖单价量一起改（300->250 @10100），失去队首
            OrderResult bid = s.submitOrder("rp-bid", "bob", "BTC", "BUY", "LIMIT", 9800, 50);
            bidId = bid.order().getOrderId();
            s.amendOrder(askId, "rp-amend-ask", 10100L, 250L);
            // bob 改价 9800 -> 9900
            s.amendOrder(bidId, "rp-amend-bid", 9900L, null);
            // alice 卖 40@9900 与 bob 的 9900 买单交叉成交 40，bob 剩 10
            s.submitOrder("rp-ask2", "alice", "BTC", "SELL", "LIMIT", 9900, 40);
            // 撤掉 alice 的 10100 卖单（剩余 150）
            s.cancel(askId);
            // 市价单混入：无卖盘，剩余撤销且抛 MARKET_NO_LIQUIDITY
            try {
                s.submitOrder("rp-mkt", "bob", "BTC", "BUY", "MARKET", 0, 20);
            } catch (TradingException ex) {
                assertEquals(RejectReason.MARKET_NO_LIQUIDITY, ex.getReason());
            }

            for (long id = 1; id <= 6; id++) {
                Order o = s.getOrder(id);
                if (o != null) {
                    ordersBefore.put(id, o.getLimitPrice() + "/" + o.getQuantity() + "/"
                            + o.getFilledQty() + "/" + o.getStatus() + "/" + o.getSeq());
                }
            }
            aliceBefore = s.snapshot("alice");
            bobBefore = s.snapshot("bob");
            OrderBook book = s.orderBook("BTC");
            bidsBefore = book.bidsSnapshot().stream().map(Order::getOrderId).toList();
            asksBefore = book.asksSnapshot().stream().map(Order::getOrderId).toList();
            log.info("[amendReplay][before] orders={} bids={} asks={} alice={} bob={}",
                    ordersBefore, bidsBefore, asksBefore, aliceBefore, bobBefore);
        }

        try (Harness h2 = start(dir)) {
            TradingService s2 = h2.service();
            assertEquals(aliceBefore, s2.snapshot("alice"), "重放后 alice 快照必须逐字段一致");
            assertEquals(bobBefore, s2.snapshot("bob"), "重放后 bob 快照必须逐字段一致");

            for (Map.Entry<Long, String> e : ordersBefore.entrySet()) {
                Order o = s2.getOrder(e.getKey());
                String after = o.getLimitPrice() + "/" + o.getQuantity() + "/"
                        + o.getFilledQty() + "/" + o.getStatus() + "/" + o.getSeq();
                assertEquals(e.getValue(), after,
                        "判定依据：订单 " + e.getKey() + " 重放后字段完全一致");
            }

            OrderBook book2 = s2.orderBook("BTC");
            assertEquals(bidsBefore, book2.bidsSnapshot().stream().map(Order::getOrderId).toList(),
                    "判定依据：买盘排队顺序重放后一致");
            assertEquals(asksBefore, book2.asksSnapshot().stream().map(Order::getOrderId).toList(),
                    "判定依据：卖盘排队顺序重放后一致");

            // 重放后改单幂等表恢复：重复同一改单请求命中幂等，不再生效
            OrderResult dupAmend = s2.amendOrder(bidId, "rp-amend-bid", 9900L, null);
            assertTrue(dupAmend.idempotent(), "判定依据：重启后重复改单必须命中幂等");
            assertEquals(bobBefore, s2.snapshot("bob"), "幂等改单不得改变账户");

            log.info("[amendReplay][after] 判定=重放一致 bids={} asks={} alice={} bob={}",
                    book2.bidsSnapshot().size(), book2.asksSnapshot().size(),
                    s2.snapshot("alice"), s2.snapshot("bob"));
        }
    }
}
