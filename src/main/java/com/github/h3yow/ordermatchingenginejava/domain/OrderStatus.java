package com.github.h3yow.ordermatchingenginejava.domain;

/**
 * 订单生命周期状态。
 * NEW      : 已进入订单簿、等待成交（含部分成交后继续挂单）
 * FILLED   : 已全部成交
 * CANCELED : 已撤销（可能发生在部分成交之后，剩余数量被撤掉）
 * REJECTED : 下单被风控/校验拒绝，未改变账户状态
 */
public enum OrderStatus {
    NEW,
    FILLED,
    CANCELED,
    REJECTED
}
