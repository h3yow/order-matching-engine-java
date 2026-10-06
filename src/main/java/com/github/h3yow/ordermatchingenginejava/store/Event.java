package com.github.h3yow.ordermatchingenginejava.store;

import com.github.h3yow.ordermatchingenginejava.domain.Fill;
import com.github.h3yow.ordermatchingenginejava.domain.Order;

/**
 * 追加到事件日志的单条记录。payload 二选一：订单或成交。
 */
public record Event(EventType type, Order order, Fill fill, long timestamp) {

    public static Event orderAccepted(Order order) {
        return new Event(EventType.ORDER_ACCEPTED, order, null, System.currentTimeMillis());
    }

    public static Event fill(Fill fill) {
        return new Event(EventType.FILL, null, fill, fill.timestamp());
    }

    public static Event orderCanceled(Order order) {
        return new Event(EventType.ORDER_CANCELED, order, null, System.currentTimeMillis());
    }

    public static Event orderAmended(Order order) {
        return new Event(EventType.ORDER_AMENDED, order, null, System.currentTimeMillis());
    }
}
