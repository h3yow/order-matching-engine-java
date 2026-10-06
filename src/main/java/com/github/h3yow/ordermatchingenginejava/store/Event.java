package com.github.h3yow.ordermatchingenginejava.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.h3yow.ordermatchingenginejava.domain.Fill;
import com.github.h3yow.ordermatchingenginejava.domain.Order;

/**
 * 追加到事件日志的单条记录。payload 按类型三选一：订单、成交或改单。
 *
 * <p>旧版本流水没有 amend 字段，反序列化时为 null，向后兼容。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Event(EventType type, Order order, Fill fill, Amend amend, long timestamp) {

    public static Event orderAccepted(Order order) {
        return new Event(EventType.ORDER_ACCEPTED, order, null, null, System.currentTimeMillis());
    }

    public static Event fill(Fill fill) {
        return new Event(EventType.FILL, null, fill, null, fill.timestamp());
    }

    public static Event orderCanceled(Order order) {
        return new Event(EventType.ORDER_CANCELED, order, null, null, System.currentTimeMillis());
    }

    public static Event orderAmended(Amend amend) {
        return new Event(EventType.ORDER_AMENDED, null, null, amend, amend.timestamp());
    }
}
