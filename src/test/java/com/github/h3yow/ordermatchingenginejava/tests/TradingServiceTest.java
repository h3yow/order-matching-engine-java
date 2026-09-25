package com.github.h3yow.ordermatchingenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.h3yow.ordermatchingenginejava.domain.OrderStatus;
import com.github.h3yow.ordermatchingenginejava.domain.RejectReason;
import com.github.h3yow.ordermatchingenginejava.domain.TradingException;
import com.github.h3yow.ordermatchingenginejava.service.AccountSnapshot;
import com.github.h3yow.ordermatchingenginejava.service.CancelResult;
import com.github.h3yow.ordermatchingenginejava.service.OrderResult;
import com.github.h3yow.ordermatchingenginejava.service.TradingService;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 服务层集成测试：每个用例使用独立临时事件日志目录，避免重放相互污染。
 * 日志打印订单标识、判定依据与账户快照。
 */
class TradingServiceTest {

    private static final Logger log = LoggerFactory.getLogger(TradingServiceTest.class);

    private static final String BTC = "BTC";
    private static final String ETH = "ETH";
    private static final String SOL = "SOL";
    private static final String XRP = "XRP";
    private static final String DOGE = "DOGE";

    private record Harness(ConfigurableApplicationContext ctx, TradingService service) implements AutoCloseable {
        @Override
        public void close() {
            ctx.close();
        }
    }

    private Harness start(@TempDir Path dir) {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(
                com.github.h3yow.ordermatchingenginejava.Application.class)
                .properties(
                        "spring.main.web-application-type=none",
                        "trading.event-log-dir=" + dir.resolve("events"),
                        "trading.seed-accounts.alice=10000000000",
                        "trading.seed-accounts.bob=10000000000",
                        "trading.seed-accounts.carol=10000000000",
                        "trading.seed-accounts.dave=0",
                        "trading.seed-positions.alice__BTC=1000000",
                        "trading.seed-positions.carol__BTC=1000000",
                        "trading.seed-positions.carol__ETH=1000000",
                        "trading.seed-positions.alice__SOL=1000000")
                .run();
        return new Harness(ctx, ctx.getBean(TradingService.class));
    }

    @Test
    @DisplayName("限价部分成交 + 重复 clientOrderId 幂等：无重复成交/扣款")
    void duplicateClientOrderIdIsIdempotent(@TempDir Path dir) {
        try (Harness h = start(dir)) {
            TradingService service = h.service();
            service.setReferencePrice(BTC, 10000);

            AccountSnapshot aliceAfterSell;
            AccountSnapshot bobAfterBuy;
            long orderId;
            // carol 卖 150 @10000（BTC 上无其他卖单，数量恰好匹配）
            OrderResult sell = service.submitOrder("dup-sell-1", "carol", BTC,
                    "SELL", "LIMIT", 10000, 150);
            assertEquals(OrderStatus.NEW, sell.order().getStatus());
            // bob 买 150 @10000，全部成交
            OrderResult buy = service.submitOrder("dup-buy-1", "bob", BTC,
                    "BUY", "LIMIT", 10000, 150);
            assertEquals(150, buy.order().getFilledQty());
            assertEquals(OrderStatus.FILLED, buy.order().getStatus());
            orderId = buy.order().getOrderId();
            aliceAfterSell = service.snapshot("carol");
            bobAfterBuy = service.snapshot("bob");

            // 重复提交同一 clientOrderId：返回同一订单、idempotent=true、无新 fill
            OrderResult buyAgain = service.submitOrder("dup-buy-1", "bob", BTC,
                    "BUY", "LIMIT", 10000, 150);
            assertTrue(buyAgain.idempotent(), "判定依据：相同 clientOrderId 命中幂等表");
            assertEquals(orderId, buyAgain.order().getOrderId());
            assertEquals(150, buyAgain.order().getFilledQty());
            assertTrue(buyAgain.fillIds().isEmpty(), "幂等命中不得附带新成交");
            assertEquals(aliceAfterSell, service.snapshot("carol"), "重复提交后快照不变");
            assertEquals(bobAfterBuy, service.snapshot("bob"), "重复提交后快照不变");

            log.info("[idempotent] sellOrder={} buyOrder={} 判定=clientOrderId命中 carolSnapshot={} bobSnapshot={}",
                    sell.order().getOrderId(), orderId,
                    service.snapshot("carol"), service.snapshot("bob"));
        }
    }

    @Test
    @DisplayName("撤单拒绝原因可区分：ORDER_NOT_FOUND / ALREADY_FILLED / ALREADY_CANCELED")
    void cancelRejectReasonsAreDistinguishable(@TempDir Path dir) {
        try (Harness h = start(dir)) {
            TradingService service = h.service();
            service.setReferencePrice(ETH, 10000);

            TradingException notFound = assertThrows(TradingException.class,
                    () -> service.cancel(999_999L));
            assertEquals(RejectReason.ORDER_NOT_FOUND, notFound.getReason());

            service.submitOrder("carol-sell-eth", "carol", ETH, "SELL", "LIMIT", 10000, 100);
            OrderResult filled = service.submitOrder("bob-buy-eth", "bob", ETH,
                    "BUY", "LIMIT", 10000, 100);
            assertEquals(OrderStatus.FILLED, filled.order().getStatus());

            TradingException alreadyFilled = assertThrows(TradingException.class,
                    () -> service.cancel(filled.order().getOrderId()));
            assertEquals(RejectReason.ORDER_ALREADY_FILLED, alreadyFilled.getReason());

            OrderResult resting = service.submitOrder("bob-rest-eth", "bob", ETH,
                    "BUY", "LIMIT", 9800, 50);
            assertEquals(OrderStatus.NEW, resting.order().getStatus());
            CancelResult canceled = service.cancel(resting.order().getOrderId());
            assertTrue(canceled.accepted());
            assertEquals(50, canceled.canceledQuantity());

            TradingException alreadyCanceled = assertThrows(TradingException.class,
                    () -> service.cancel(resting.order().getOrderId()));
            assertEquals(RejectReason.ORDER_ALREADY_CANCELED, alreadyCanceled.getReason());

            log.info("[cancelReasons] orderId=999999 判定={}; filledOrder={} 判定={}; canceledOrder={} 判定={}",
                    notFound.getReason(), filled.order().getOrderId(), alreadyFilled.getReason(),
                    resting.order().getOrderId(), alreadyCanceled.getReason());
        }
    }

    @Test
    @DisplayName("部分成交后撤单：剩余被撤，冻结回补，可卖=总量")
    void partialFillThenCancel(@TempDir Path dir) {
        try (Harness h = start(dir)) {
            TradingService service = h.service();
            service.setReferencePrice(SOL, 10000);
            // carol 在 10000 买 100
            service.submitOrder("carol-buy-sol", "carol", SOL, "BUY", "LIMIT", 10000, 100);
            // alice 卖 300 @10000，成交 100，剩 200 挂单
            OrderResult sell = service.submitOrder("alice-sell-sol", "alice", SOL,
                    "SELL", "LIMIT", 10000, 300);
            assertEquals(100, sell.order().getFilledQty());
            assertEquals(200, sell.order().remainingQty());

            long[] posAfterPartial = service.snapshot("alice").positions().get(SOL);
            assertEquals(999_900, posAfterPartial[0], "总持仓减少 100");
            assertEquals(999_700, posAfterPartial[1], "可卖持仓冻结 300");

            CancelResult canceled = service.cancel(sell.order().getOrderId());
            assertTrue(canceled.accepted());
            assertEquals(200, canceled.canceledQuantity(), "判定依据：撤销剩余 200");
            assertEquals(OrderStatus.CANCELED, sell.order().getStatus());

            long[] posFinal = service.snapshot("alice").positions().get(SOL);
            assertEquals(999_900, posFinal[0], "总持仓只反映已成交的 100");
            assertEquals(999_900, posFinal[1], "撤单后冻结回补，可卖=总量");

            log.info("[partialThenCancel] orderId={} clientOrderId={} filled=100 canceled=200 快照={}",
                    sell.order().getOrderId(), sell.order().getClientOrderId(),
                    service.snapshot("alice"));
        }
    }

    @Test
    @DisplayName("风控拒绝可区分：超单笔数量 / 价格偏离 / 资金不足，且账户状态不变")
    void riskChecksRejectWithoutStateChange(@TempDir Path dir) {
        try (Harness h = start(dir)) {
            TradingService service = h.service();
            service.setReferencePrice(XRP, 10000);
            AccountSnapshot before = service.snapshot("bob");

            TradingException tooMany = assertThrows(TradingException.class,
                    () -> service.submitOrder("risk-qty", "bob", XRP, "BUY", "LIMIT",
                            10000, 2_000_000));
            assertEquals(RejectReason.QUANTITY_EXCEEDS_LIMIT, tooMany.getReason());

            TradingException badPrice = assertThrows(TradingException.class,
                    () -> service.submitOrder("risk-price", "bob", XRP, "BUY", "LIMIT",
                            20000, 10));
            assertEquals(RejectReason.PRICE_OUT_OF_BAND, badPrice.getReason());

            // 零资金账户 dave：数量合法、价格合理，但冻结后可用现金为负 -> INSUFFICIENT_AVAILABLE
            TradingException noCash = assertThrows(TradingException.class,
                    () -> service.submitOrder("risk-cash", "dave", XRP, "BUY", "LIMIT",
                            10000, 100));
            assertEquals(RejectReason.INSUFFICIENT_AVAILABLE, noCash.getReason());

            assertEquals(before, service.snapshot("bob"), "判定依据：拒绝后账户快照不变");
            assertEquals(new AccountSnapshot("dave", 0, 0, java.util.Map.of()),
                    service.snapshot("dave"), "零资金账户被拒后快照保持初始值");

            log.info("[riskReject] clientOrderId=risk-qty 判定={}; risk-price 判定={}; risk-cash 判定={}; bobSnapshot={}",
                    tooMany.getReason(), badPrice.getReason(), noCash.getReason(),
                    service.snapshot("bob"));
        }
    }

    @Test
    @DisplayName("无持仓卖单被拒：INSUFFICIENT_AVAILABLE")
    void sellWithoutPositionRejected(@TempDir Path dir) {
        try (Harness h = start(dir)) {
            TradingService service = h.service();
            service.setReferencePrice(DOGE, 10000);
            TradingException ex = assertThrows(TradingException.class,
                    () -> service.submitOrder("bob-short", "bob", DOGE, "SELL", "LIMIT",
                            10000, 10));
            assertEquals(RejectReason.INSUFFICIENT_AVAILABLE, ex.getReason());
            log.info("[sellReject] clientOrderId=bob-short 判定={} 快照={}",
                    ex.getReason(), service.snapshot("bob"));
        }
    }
}
