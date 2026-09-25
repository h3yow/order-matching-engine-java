package com.github.h3yow.ordermatchingenginejava.domain;

/**
 * 业务拒绝异常：携带可区分的拒绝原因。风控拒绝保证“不改变账户状态”，
 * 由统一异常处理转换为 HTTP 422 响应。
 */
public class TradingException extends RuntimeException {

    private final RejectReason reason;

    public TradingException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
