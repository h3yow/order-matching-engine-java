package com.github.h3yow.ordermatchingenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.h3yow.ordermatchingenginejava.domain.OrderStatus;
import com.github.h3yow.ordermatchingenginejava.domain.RejectReason;
import com.github.h3yow.ordermatchingenginejava.domain.TradingException;
import com.github.h3yow.ordermatchingenginejava.service.AccountSnapshot;
import com.github.h3yow.ordermatchingenginejava.service.OrderResult;
import com.github.h3yow.ordermatchingenginejava.service.TradingService;
import java.nio.file.Path;
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
 * 并发场景测试：
 * 1) 多线程同时用同一账户买入同一卖盘，成交总量不得超过卖盘数量，账户不得出现负可用现金；
 * 2) 多线程同时使用相同 clientOrderId 提交，只能有一次真实成交，其余全部幂等返回；
 * 3) 并发撤单与下单下，订单状态与账户守恒。
 */
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

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
                        "trading.seed-accounts.bob=100000000",
                        "trading.seed-accounts.carol=100000000",
                        "trading.seed-positions.alice__BTC=1000000",
                        "trading.max-order-quantity=1000000",
                        "trading.max-deviation-bps=100000")
                .run();
        return new Harness(ctx, ctx.getBean(TradingService.class));
    }

    @Test
    @DisplayName("并发买入：总成交量不超过卖盘，账户可用现金不为负")
    void concurrentBuysNeverOversell(@TempDir Path dir) throws Exception {
        int threads = 16;
        int perThread = 20;
        try (Harness h = start(dir)) {
            TradingService s = h.service();
            s.setReferencePrice("BTC", 10000);
            // alice 卖盘总量 1000（=10000000 定点），多个买家并发吃单
            OrderResult ask = s.submitOrder("ask-pool", "alice", "BTC",
                    "SELL", "LIMIT", 10000, 100_000);
            assertEquals(OrderStatus.NEW, ask.order().getStatus());

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger filled = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            for (int t = 0; t < threads; t++) {
                final int idx = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            try {
                                // bob/carol 交替作为买方，每单 100
                                String buyer = (idx % 2 == 0) ? "bob" : "carol";
                                OrderResult r = s.submitOrder(
                                        "buy-" + idx + "-" + i, buyer, "BTC",
                                        "BUY", "LIMIT", 10000, 1000);
                                if (r.order().getStatus() != OrderStatus.REJECTED) {
                                    filled.addAndGet((int) r.order().getFilledQty());
                                }
                            } catch (TradingException ex) {
                                rejected.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

            long totalBuyFilled = filled.get();
            long aliceSold = 1_000_000 - s.snapshot("alice").positions().get("BTC")[0];
            long bobPos = pos(s, "bob", "BTC");
            long carolPos = pos(s, "carol", "BTC");

            log.info("[concurrentBuy] threads={} filledUnits={} rejectedOrders={} aliceSold={} bobPos={} carolPos={}",
                    threads, totalBuyFilled, rejected.get(), aliceSold, bobPos, carolPos);
            log.info("[concurrentBuy] 账户快照 alice={} bob={} carol={}",
                    s.snapshot("alice"), s.snapshot("bob"), s.snapshot("carol"));

            assertEquals(aliceSold, totalBuyFilled, "判定依据：卖方成交=所有买方成交之和");
            assertEquals(aliceSold, bobPos + carolPos, "判定依据：双边持仓守恒");
            assertTrue(aliceSold <= 100_000, "判定依据：成交量不得超过卖盘 100000");
            assertTrue(s.snapshot("bob").availableCash() >= 0, "判定依据：不得出现负可用现金");
            assertTrue(s.snapshot("carol").availableCash() >= 0, "判定依据：不得出现负可用现金");
        }
    }

    private long pos(TradingService s, String acc, String symbol) {
        return s.snapshot(acc).positions().getOrDefault(symbol, new long[2])[0];
    }

    @Test
    @DisplayName("并发相同 clientOrderId：只产生一次成交，其余全部幂等")
    void concurrentDuplicateClientOrderId(@TempDir Path dir) throws Exception {
        int threads = 20;
        try (Harness h = start(dir)) {
            TradingService s = h.service();
            s.setReferencePrice("BTC", 10000);
            s.submitOrder("dup-ask", "alice", "BTC", "SELL", "LIMIT", 10000, 50_000);

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger idempotentHits = new AtomicInteger();
            AtomicInteger filled = new AtomicInteger();
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        OrderResult r = s.submitOrder("same-client-id", "bob", "BTC",
                                "BUY", "LIMIT", 10000, 10_000);
                        if (r.idempotent()) {
                            idempotentHits.incrementAndGet();
                        } else {
                            filled.addAndGet((int) r.order().getFilledQty());
                        }
                    } catch (Exception e) {
                        // 不允许出现异常拒绝：除了第一次，其余都必须是幂等返回
                        throw new RuntimeException(e);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

            AccountSnapshot bob = s.snapshot("bob");
            long aliceSold = 1_000_000 - s.snapshot("alice").positions().get("BTC")[0];
            log.info("[concurrentDup] threads={} idempotentHits={} realFilled={} aliceSold={} bob={}",
                    threads, idempotentHits.get(), filled.get(), aliceSold, bob);

            assertEquals(threads - 1, idempotentHits.get(),
                    "判定依据：除首个请求外，其余全部命中幂等");
            assertEquals(10_000, aliceSold, "判定依据：相同 clientOrderId 只允许成交一次");
            assertEquals(10_000, pos(s, "bob", "BTC"));
        }
    }

    @Test
    @DisplayName("并发下单+撤单竞争：撤已成交单返回 ALREADY_FILLED，状态守恒")
    void concurrentCancelRace(@TempDir Path dir) throws Exception {
        try (Harness h = start(dir)) {
            TradingService s = h.service();
            s.setReferencePrice("BTC", 10000);
            // 预先准备对手盘：alice 的卖单会与 bob 买单即时成交
            s.submitOrder("race-ask", "alice", "BTC", "SELL", "LIMIT", 10000, 10_000);

            OrderResult buy = s.submitOrder("race-buy", "bob", "BTC",
                    "BUY", "LIMIT", 10000, 10_000);
            long orderId = buy.order().getOrderId();

            // 撤单与另一线程重复提交同时进行；重复提交必须幂等，撤单必须可解释
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger idempotent = new AtomicInteger();
            AtomicInteger alreadyFilled = new AtomicInteger();
            for (int t = 0; t < 2; t++) {
                final int which = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        if (which == 0) {
                            try {
                                s.cancel(orderId);
                            } catch (TradingException ex) {
                                if (ex.getReason() == RejectReason.ORDER_ALREADY_FILLED) {
                                    alreadyFilled.incrementAndGet();
                                }
                            }
                        } else {
                            OrderResult r = s.submitOrder("race-buy", "bob", "BTC",
                                    "BUY", "LIMIT", 10000, 10_000);
                            if (r.idempotent()) {
                                idempotent.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

            assertEquals(OrderStatus.FILLED, s.getOrder(orderId).getStatus());
            assertEquals(1, idempotent.get());
            assertEquals(1, alreadyFilled.get(), "判定依据：全成交后撤单得到 ALREADY_FILLED");
            assertEquals(10_000, pos(s, "bob", "BTC"));
            log.info("[cancelRace] orderId={} 判定=FILLED 后撤单原因 ALREADY_FILLED 快照={}",
                    orderId, s.snapshot("bob"));
        }
    }
}
