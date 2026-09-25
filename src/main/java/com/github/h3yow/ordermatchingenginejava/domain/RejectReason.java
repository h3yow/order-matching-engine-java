package com.github.h3yow.ordermatchingenginejava.domain;

/** 拒绝原因，要求彼此可区分。 */
public enum RejectReason {
    /** 同一客户端订单号重复提交。 */
    DUPLICATE_CLIENT_ORDER_ID,
    /** 超过单笔最大数量。 */
    EXCEED_MAX_ORDER_QUANTITY,
    /** 可用余额不足（买入）。 */
    INSUFFICIENT_BALANCE,
    /** 可用持仓不足（卖出）。 */
    INSUFFICIENT_POSITION,
    /** 价格超出合理性区间。 */
    PRICE_OUT_OF_RANGE,
    /** 订单不存在，无法撤销。 */
    ORDER_NOT_FOUND,
    /** 订单已完全成交，无法撤销。 */
    ORDER_ALREADY_FILLED,
    /** 订单已撤销，无法重复撤销。 */
    ORDER_ALREADY_CANCELED,
    /** 订单已被拒绝，无法撤销。 */
    ORDER_ALREADY_REJECTED
}
