package com.github.h3yow.ordermatchingenginejava.engine;

import com.github.h3yow.ordermatchingenginejava.domain.Order;
import com.github.h3yow.ordermatchingenginejava.domain.Side;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 单一标的的订单簿：价格优先、时间优先。
 *
 * <p>买盘按价格从高到低（{@link NavigableMap#descendingMap}），卖盘按价格从低到高；
 * 同一价格内用 FIFO 队列保证时间优先（订单 seq 单调递增，入队顺序即时间顺序）。</p>
 *
 * <p>非线程安全：每个标的一把串行锁由 TradingService 统一持有。</p>
 */
public final class OrderBook {

    /** price -> FIFO 队列。 */
    private final NavigableMap<Long, Deque<Order>> bids = new TreeMap<>();
    private final NavigableMap<Long, Deque<Order>> asks = new TreeMap<>();

    /** 入簿（仅挂未成交完的限价单）。 */
    public void add(Order order) {
        Deque<Order> level = bookOf(order.getSide())
                .computeIfAbsent(order.getLimitPrice(), p -> new ArrayDeque<>());
        level.addLast(order);
    }

    /** 撤单/剩余数量取消时出簿。 */
    public void remove(Order order) {
        Deque<Order> level = bookOf(order.getSide()).get(order.getLimitPrice());
        if (level != null) {
            level.remove(order);
            if (level.isEmpty()) {
                bookOf(order.getSide()).remove(order.getLimitPrice());
            }
        }
    }

    /** 买方最优：价格最高且队首仍有剩余数量的挂单；没有则 null。 */
    public Order bestBid() {
        return best(bids.descendingMap());
    }

    /** 卖方最优：价格最低且队首仍有剩余数量的挂单；没有则 null。 */
    public Order bestAsk() {
        return best(asks);
    }

    /**
     * 取最优队首；若队首订单已无剩余（正常情况下成交后会被立即弹出），
     * 惰性清理后继续找下一档，保证返回的订单一定可成交。
     */
    private Order best(NavigableMap<Long, Deque<Order>> ordered) {
        while (!ordered.isEmpty()) {
            Map.Entry<Long, Deque<Order>> top = ordered.firstEntry();
            Deque<Order> queue = top.getValue();
            while (!queue.isEmpty()) {
                Order o = queue.peekFirst();
                if (o.remainingQty() > 0 && o.isLive()) {
                    return o;
                }
                queue.pollFirst();
            }
            ordered.remove(top.getKey());
        }
        return null;
    }

    private NavigableMap<Long, Deque<Order>> bookOf(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    /** 买盘快照：价格由高到低，每档列出仍有剩余的挂单。 */
    public List<Order> bidsSnapshot() {
        return snapshot(bids.descendingMap());
    }

    /** 卖盘快照：价格由低到高，每档列出仍有剩余的挂单。 */
    public List<Order> asksSnapshot() {
        return snapshot(asks);
    }

    private List<Order> snapshot(NavigableMap<Long, Deque<Order>> ordered) {
        List<Order> out = new ArrayList<>();
        for (Deque<Order> queue : ordered.values()) {
            for (Order o : queue) {
                if (o.remainingQty() > 0 && o.isLive()) {
                    out.add(o);
                }
            }
        }
        return out;
    }

    public int bidLevelCount() {
        return bids.size();
    }

    public int askLevelCount() {
        return asks.size();
    }
}
