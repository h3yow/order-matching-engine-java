package com.github.h3yow.ordermatchingenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.h3yow.ordermatchingenginejava.domain.OrderStatus;
import com.github.h3yow.ordermatchingenginejava.domain.RejectReason;
import com.github.h3yow.ordermatchingenginejava.domain.TradingException;
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

/** 市价单测试：部分成交剩余撤销、完全无流动性拒绝、保护带停撮合。 */
class MarketOrderTest {

    private static final Logger log = LoggerFactory.getLogger(MarketOrderTest.class);

    private TradingService start(Path dir) {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(
                com.github.h3yow.ordermatchingenginejava.Application.class)
                .properties(
                        "spring.main.web-application-type=none",
                        "trading.event-log-dir=" + dir.resolve("events"),
                        "trading.seed-accounts.alice=10000000000",
                        "trading.seed-accounts.bob=10000000000",
                        "trading.seed-positions.alice__BTC=1000000",
                        "trading.max-deviation-bps=500")
                .run();
        return ctx.getBean(TradingService.class);
    }

    @Test
    @DisplayName("市价买单部分成交：吃 100 后剩余撤销，现金不多扣")
    void marketBuyPartialFill(@TempDir Path dir) {
        TradingService s = start(dir);
        s.setReferencePrice("BTC", 10000);
        s.submitOrder("m-ask", "alice", "BTC", "SELL", "LIMIT", 10000, 100);

        long cashBefore = s.snapshot("bob").cash();
        OrderResult r = s.submitOrder("m-buy", "bob", "BTC", "BUY", "MARKET", 0, 300);
        assertEquals(100, r.order().getFilledQty(), "判定依据：只有 100 卖盘");
        assertEquals(200, r.order().remainingQty());
        assertEquals(OrderStatus.CANCELED, r.order().getStatus(), "市价剩余必须撤销");
        assertEquals(cashBefore - 100, s.snapshot("bob").cash(), "现金只扣实际成交额 100");
        assertEquals(cashBefore - 100, s.snapshot("bob").availableCash(),
                "撤销后冻结全部回补，可用现金等于现金");
        assertEquals(100, s.snapshot("bob").positions().get("BTC")[0]);
        log.info("[marketPartial] orderId={} filled=100 canceled=200 快照={}",
                r.order().getOrderId(), s.snapshot("bob"));
    }

    @Test
    @DisplayName("市价单无流动性：MARKET_NO_LIQUIDITY 且账户零变化")
    void marketNoLiquidity(@TempDir Path dir) {
        TradingService s = start(dir);
        s.setReferencePrice("BTC", 10000);
        long cashBefore = s.snapshot("bob").cash();
        TradingException ex = assertThrows(TradingException.class,
                () -> s.submitOrder("m-noliq", "bob", "BTC", "BUY", "MARKET", 0, 100));
        assertEquals(RejectReason.MARKET_NO_LIQUIDITY, ex.getReason());
        assertEquals(cashBefore, s.snapshot("bob").cash(), "判定依据：无成交不扣款");
        assertEquals(cashBefore, s.snapshot("bob").availableCash());
        log.info("[marketNoLiq] clientOrderId=m-noliq 判定={} 快照={}",
                ex.getReason(), s.snapshot("bob"));
    }

    @Test
    @DisplayName("市价卖单：无持仓被 INSUFFICIENT_AVAILABLE 拒绝")
    void marketSellNoPosition(@TempDir Path dir) {
        TradingService s = start(dir);
        s.setReferencePrice("ETH", 10000);
        TradingException ex = assertThrows(TradingException.class,
                () -> s.submitOrder("m-short", "bob", "ETH", "SELL", "MARKET", 0, 10));
        assertEquals(RejectReason.INSUFFICIENT_AVAILABLE, ex.getReason());
        log.info("[marketSellReject] clientOrderId=m-short 判定={}", ex.getReason());
    }
}
