package com.github.h3yow.ordermatchingenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.h3yow.ordermatchingenginejava.domain.Order;
import com.github.h3yow.ordermatchingenginejava.domain.OrderStatus;
import com.github.h3yow.ordermatchingenginejava.domain.OrderType;
import com.github.h3yow.ordermatchingenginejava.domain.Side;
import com.github.h3yow.ordermatchingenginejava.engine.Match;
import com.github.h3yow.ordermatchingenginejava.engine.MatchingEngine;
import com.github.h3yow.ordermatchingenginejava.engine.OrderBook;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** OrderBook + MatchingEngine 的价格优先/时间优先/部分成交规则单测。 */
class MatchingEngineTest {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngineTest.class);

    private MatchingEngine engine;
    private OrderBook book;
    private long seq;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        book = new OrderBook();
        seq = 0;
    }

    private Order order(String cid, Side side, OrderType type, long price, long qty) {
        Order o = new Order();
        o.setOrderId(seq + 1);
        o.setSeq(++seq);
        o.setClientOrderId(cid);
        o.setAccountId("acc-" + cid);
        o.setSymbol("BTC");
        o.setSide(side);
        o.setType(type);
        o.setLimitPrice(price);
        o.setQuantity(qty);
        o.setFilledQty(0L);
        o.setStatus(OrderStatus.NEW);
        return o;
    }

    @Test
    @DisplayName("价格优先：买单优先与最低卖价成交")
    void pricePriority() {
        Order ask1 = order("a1", Side.SELL, OrderType.LIMIT, 10100, 200);
        Order ask2 = order("a2", Side.SELL, OrderType.LIMIT, 10000, 200);
        book.add(ask1);
        book.add(ask2);

        Order bid = order("b1", Side.BUY, OrderType.LIMIT, 10200, 300);
        List<Match> matches = engine.match(bid, book, p -> true);

        assertEquals(2, matches.size(), "判定依据：先吃 10000 再吃 10100");
        assertSame(ask2, matches.get(0).maker(), "第一笔对手应为最低卖价 a2");
        assertEquals(10000, matches.get(0).price());
        assertEquals(200, matches.get(0).quantity());
        assertSame(ask1, matches.get(1).maker());
        assertEquals(10100, matches.get(1).price());
        assertEquals(100, matches.get(1).quantity(), "判定依据：taker 仅剩 100");
        assertEquals(300, bid.getFilledQty());
        assertEquals(OrderStatus.NEW, bid.getStatus(), "引擎不设置终态，由服务层设置");
        assertEquals(200, ask2.getFilledQty());
        assertEquals(100, ask1.getFilledQty());
        assertSame(ask1, book.bestAsk(), "判定依据：10100 档还剩 100，为最优卖价");
        log.info("[pricePriority] orderId={} 判定=价格优先 fills={} 剩余={}",
                bid.getOrderId(), matches.size(), bid.remainingQty());
    }

    @Test
    @DisplayName("时间优先：同价格按入簿 FIFO 成交")
    void timePriority() {
        Order first = order("s1", Side.SELL, OrderType.LIMIT, 10000, 100);
        Order second = order("s2", Side.SELL, OrderType.LIMIT, 10000, 100);
        book.add(first);
        book.add(second);

        Order bid = order("b1", Side.BUY, OrderType.LIMIT, 10000, 150);
        List<Match> matches = engine.match(bid, book, p -> true);

        assertEquals(2, matches.size());
        assertSame(first, matches.get(0).maker(), "判定依据：同价位 s1 先入簿先成交");
        assertEquals(100, matches.get(0).quantity());
        assertSame(second, matches.get(1).maker());
        assertEquals(50, matches.get(1).quantity());
        assertEquals(100, first.getFilledQty());
        assertEquals(50, second.getFilledQty());
        assertEquals(150, bid.getFilledQty());
        assertSame(second, book.bestAsk(), "s2 部分成交后仍在最优价位");
        log.info("[timePriority] 判定=FIFO first.filled={}, second.filled={}, bestAskId={}",
                first.getFilledQty(), second.getFilledQty(), book.bestAsk().getClientOrderId());
    }

    @Test
    @DisplayName("部分成交：限价单剩余数量留在订单簿")
    void partialFillRestsInBook() {
        Order ask = order("a1", Side.SELL, OrderType.LIMIT, 10000, 100);
        book.add(ask);

        Order bid = order("b1", Side.BUY, OrderType.LIMIT, 10000, 300);
        List<Match> matches = engine.match(bid, book, p -> true);

        assertEquals(1, matches.size());
        assertEquals(100, bid.getFilledQty());
        assertEquals(200, bid.remainingQty());
        assertSame(bid, book.bestBid(), "判定依据：限价买单剩余 200 入簿");
        assertNull(book.bestAsk());
        log.info("[partialFillRests] orderId={} filled={} remaining={} 入簿买一={}",
                bid.getClientOrderId(), bid.getFilledQty(), bid.remainingQty(),
                book.bestBid().getClientOrderId());
    }

    @Test
    @DisplayName("市价单：成交后剩余不入簿，且受价格保护谓词约束")
    void marketOrderDoesNotRestAndGuarded() {
        Order cheap = order("a1", Side.SELL, OrderType.LIMIT, 10000, 50);
        Order expensive = order("a2", Side.SELL, OrderType.LIMIT, 12000, 50);
        book.add(cheap);
        book.add(expensive);

        Order market = order("m1", Side.BUY, OrderType.MARKET, 0, 200);
        // 参考价 10000，5% 保护带 -> 允许 <=10500
        List<Match> matches = engine.match(market, book, p -> Math.abs(p - 10000) * 10000L / 10000 <= 500);

        assertEquals(1, matches.size());
        assertEquals(50, market.getFilledQty());
        assertEquals(150, market.remainingQty(), "判定依据：12000 超保护带，停止撮合");
        assertNull(book.bestBid(), "市价单剩余不入簿");
        assertSame(expensive, book.bestAsk(), "12000 的卖单保持挂单");
        log.info("[marketGuarded] orderId={} filled={} remaining={} 保护带拦截后续档位",
                market.getClientOrderId(), market.getFilledQty(), market.remainingQty());
    }

    @Test
    @DisplayName("限价不交叉则不成交并直接入簿")
    void noCrossNoTrade() {
        Order ask = order("a1", Side.SELL, OrderType.LIMIT, 11000, 100);
        book.add(ask);

        Order bid = order("b1", Side.BUY, OrderType.LIMIT, 10000, 100);
        List<Match> matches = engine.match(bid, book, p -> true);

        assertTrue(matches.isEmpty(), "判定依据：买价 10000 < 卖价 11000");
        assertSame(bid, book.bestBid());
        assertSame(ask, book.bestAsk());
        assertEquals(0, bid.getFilledQty());
    }
}
