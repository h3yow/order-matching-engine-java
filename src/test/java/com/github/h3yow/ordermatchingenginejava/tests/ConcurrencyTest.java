package com.github.h3yow.ordermatchingenginejava.tests;

import static org.junit.jupiter.api.Assertions.*;

import com.github.h3yow.ordermatchingenginejava.account.Account;
import com.github.h3yow.ordermatchingenginejava.account.AccountService;
import com.github.h3yow.ordermatchingenginejava.domain.OrderType;
import com.github.h3yow.ordermatchingenginejava.domain.RejectReason;
import com.github.h3yow.ordermatchingenginejava.domain.Side;
import com.github.h3yow.ordermatchingenginejava.engine.MatchingEngine;
import com.github.h3yow.ordermatchingenginejava.engine.OrderResult;
import com.github.h3yow.ordermatchingenginejava.persistence.Journal;
import com.github.h3yow.ordermatchingenginejava.risk.RiskChecker;
import com.github.h3yow.ordermatchingenginejava.risk.RiskLimits;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 并发下单/重复提交场景：状态守恒、不超限额、不出现负余额。 */
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

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

    @Test
    void concurrentSellOrdersNeverOversellPosition() throws Exception {
        int threads = 32;
        long qtyPerOrder = 1_000; // 32 * 1000 = 32000 << 默认持仓，先制造拥挤但不超限
        List<OrderResult> results = runConcurrent(threads,
                i -> engine.submitOrder("c-s" + i, "seller", Side.SELL, OrderType.LIMIT,
                        10000L, qtyPerOrder));

        Account seller = accountService.getOrCreate("seller");
        long accepted = results.stream().filter(OrderResult::isAccepted).count();
        assertEquals(threads, accepted);
        assertEquals(threads * qtyPerOrder, seller.getPositionFrozen(), "冻结总量应等于已受理数量");
        assertTrue(seller.getPositionAvailable() >= 0, "不得出现负持仓");
        log.info("[ASSERT] 并发卖出 orderIds={} 判定依据: 冻结总量==受理总量 {}",
                accepted, seller.snapshot());
    }

    @Test
    void concurrentBuyOrdersNeverExceedBalance() throws Exception {
        // 每笔冻结 10000*100=1,000,000；默认资金 10,000,000 → 最多 10 笔通过
        int threads = 50;
        List<OrderResult> results = runConcurrent(threads,
                i -> engine.submitOrder("c-b" + i, "buyer", Side.BUY, OrderType.LIMIT,
                        10000L, 100));

        Account buyer = accountService.getOrCreate("buyer");
        long accepted = results.stream().filter(OrderResult::isAccepted).count();
        long rejected = results.stream()
                .filter(r -> !r.isAccepted()
                        && r.getRejectReason() == RejectReason.INSUFFICIENT_BALANCE)
                .count();
        assertEquals(10, accepted, "通过数量不得超过资金允许上限");
        assertEquals(threads - 10, rejected);
        assertTrue(buyer.getCashAvailable() >= 0, "不得出现负余额");
        assertEquals(limits.getDefaultCash(),
                buyer.getCashAvailable() + buyer.getCashFrozen(), "资金总量守恒");
        log.info("[ASSERT] 并发买入 accepted={} rejected={} 判定依据: 余额上限不被突破 {}",
                accepted, rejected, buyer.snapshot());
    }

    @Test
    void concurrentDuplicateSubmissionsApplyExactlyOnce() throws Exception {
        int threads = 16;
        // 全部线程使用同一 clientOrderId
        List<OrderResult> results = runConcurrent(threads,
                i -> engine.submitOrder("c-dup", "seller", Side.SELL, OrderType.LIMIT,
                        10000L, 100));

        long accepted = results.stream().filter(OrderResult::isAccepted).count();
        long dupRejected = results.stream()
                .filter(r -> !r.isAccepted()
                        && r.getRejectReason() == RejectReason.DUPLICATE_CLIENT_ORDER_ID)
                .count();
        assertEquals(1, accepted, "同一 clientOrderId 并发提交只能成功一次");
        assertEquals(threads - 1, dupRejected);

        Account seller = accountService.getOrCreate("seller");
        assertEquals(100, seller.getPositionFrozen(), "只冻结一次，不得重复扣减");
        log.info("[ASSERT] 并发重复提交 accepted={} dupRejected={} 判定依据: 幂等去重 {}",
                accepted, dupRejected, seller.snapshot());
    }

    @Test
    void concurrentCrossingTradesConserveState() throws Exception {
        int pairs = 20;
        ExecutorService pool = Executors.newFixedThreadPool(pairs * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                await(start);
                engine.submitOrder("c-s" + n, "seller" + n, Side.SELL, OrderType.LIMIT,
                        10000L, 50);
            }));
            futures.add(pool.submit(() -> {
                await(start);
                engine.submitOrder("c-b" + n, "buyer" + n, Side.BUY, OrderType.LIMIT,
                        10000L, 50);
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        long totalCash = 0;
        long totalPos = 0;
        for (int i = 0; i < pairs; i++) {
            Account b = accountService.getOrCreate("buyer" + i);
            Account s = accountService.getOrCreate("seller" + i);
            totalCash += b.getCashAvailable() + b.getCashFrozen()
                    + s.getCashAvailable() + s.getCashFrozen();
            totalPos += b.getPositionAvailable() + b.getPositionFrozen()
                    + s.getPositionAvailable() + s.getPositionFrozen();
            assertTrue(b.getCashAvailable() >= 0 && b.getCashFrozen() >= 0);
            assertTrue(s.getPositionAvailable() >= 0 && s.getPositionFrozen() >= 0);
        }
        assertEquals(2L * pairs * limits.getDefaultCash(), totalCash, "并发成交后现金总量守恒");
        assertEquals(2L * pairs * limits.getDefaultPosition(), totalPos, "并发成交后持仓总量守恒");
        log.info("[ASSERT] 并发撮合 pairs={} 判定依据: 全账户现金/持仓总量守恒 totalCash={} totalPos={}",
                pairs, totalCash, totalPos);
    }

    private List<OrderResult> runConcurrent(int threads,
                                            java.util.function.IntFunction<OrderResult> task)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OrderResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                await(start);
                return task.apply(n);
            }));
        }
        start.countDown();
        List<OrderResult> results = new ArrayList<>();
        for (Future<OrderResult> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();
        return results;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
