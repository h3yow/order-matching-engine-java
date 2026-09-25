package com.github.h3yow.ordermatchingenginejava.domain;

/** 订单生命周期状态。 */
public enum OrderStatus {
    /** 已接受，尚未成交（可能在簿中）。 */
    OPEN,
    /** 部分成交，剩余数量仍在簿中。 */
    PARTIALLY_FILLED,
    /** 全部成交。 */
    FILLED,
    /** 已撤销（含部分成交后撤销剩余）。 */
    CANCELED,
    /** 被风控或校验拒绝。 */
    REJECTED
}
