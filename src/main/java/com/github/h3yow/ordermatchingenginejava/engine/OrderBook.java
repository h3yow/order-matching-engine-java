package com.github.h3yow.ordermatchingenginejava.engine;

import com.github.h3yow.ordermatchingenginejava.domain.Order;
import com.github.h3yow.ordermatchingenginejava.domain.OrderType;
import com.github.h3yow.ordermatchingenginejava.domain.Side;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.TreeMap;

/**
 * 单标的订单簿：价格优先、时间优先。
 * 买单按价格降序、卖单按价格升序；同价位按撮合序号（时间）先入先出。
 * 非线程安全，由撮合引擎统一加锁。
 */
public class OrderBook {

    /** 买侧：价格降序。 */
    private final TreeMap<Long, Deque<Order>> bids = new TreeMap<>((a, b) -> Long.compare(b, a));
    /** 卖侧：价格升序。 */
    private final TreeMap<Long, Deque<Order>> asks = new TreeMap<>();

    /** 将剩余数量挂入簿中。 */
    public void add(Order order) {
        sideOf(order).computeIfAbsent(order.getPrice(), k -> new ArrayDeque<>()).addLast(order);
    }

    /** 从簿中移除订单（撤单或完全成交）。 */
    public void remove(Order order) {
        TreeMap<Long, Deque<Order>> side = sideOf(order);
        Deque<Order> queue = side.get(order.getPrice());
        if (queue != null) {
            queue.remove(order);
            if (queue.isEmpty()) {
                side.remove(order.getPrice());
            }
        }
    }

    /**
     * 与传入订单撮合，返回成交列表（maker, 成交价, 成交量）。
     * 成交价为对手方（maker）限价，符合价格优先原则。
     * 调用方负责根据返回结果更新订单与账户状态，并在 maker 完全成交时调用 remove。
     *
     * @param marketBuyPriceCap 市价买单可接受的最高成交价（风控冻结估算价），
     *                          防止市价单在极端卖价下透支；其他情况传 null
     */
    public List<Match> match(Order incoming, Long marketBuyPriceCap) {
        List<Match> matches = new ArrayList<>();
        TreeMap<Long, Deque<Order>> opposite =
                (incoming.getSide() == Side.BUY) ? asks : bids;

        while (incoming.getRemainingQuantity() > 0 && !opposite.isEmpty()) {
            var entry = opposite.firstEntry();
            long makerPrice = entry.getKey();
            if (!priceCrossable(incoming, makerPrice, marketBuyPriceCap)) {
                break;
            }
            Deque<Order> queue = entry.getValue();
            while (incoming.getRemainingQuantity() > 0 && !queue.isEmpty()) {
                Order maker = queue.peekFirst();
                long qty = Math.min(incoming.getRemainingQuantity(), maker.getRemainingQuantity());
                matches.add(new Match(maker, makerPrice, qty));
                incoming.addFill(qty);
                maker.addFill(qty);
                if (maker.getRemainingQuantity() == 0) {
                    queue.pollFirst();
                }
            }
            if (queue.isEmpty()) {
                opposite.pollFirstEntry();
            }
        }
        return matches;
    }

    private boolean priceCrossable(Order incoming, long makerPrice, Long marketBuyPriceCap) {
        if (incoming.getType() == OrderType.MARKET) {
            return marketBuyPriceCap == null || makerPrice <= marketBuyPriceCap;
        }
        return incoming.getSide() == Side.BUY
                ? incoming.getPrice() >= makerPrice
                : incoming.getPrice() <= makerPrice;
    }

    private TreeMap<Long, Deque<Order>> sideOf(Order order) {
        return order.getSide() == Side.BUY ? bids : asks;
    }

    /** 当前最优买价，无买单返回 null。 */
    public Long bestBid() {
        return bids.isEmpty() ? null : bids.firstKey();
    }

    /** 当前最优卖价，无卖单返回 null。 */
    public Long bestAsk() {
        return asks.isEmpty() ? null : asks.firstKey();
    }

    /** 一笔撮合结果。 */
    public record Match(Order maker, long price, long quantity) {}
}
