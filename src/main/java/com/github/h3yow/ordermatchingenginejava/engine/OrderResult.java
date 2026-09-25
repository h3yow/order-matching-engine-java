package com.github.h3yow.ordermatchingenginejava.engine;

import com.github.h3yow.ordermatchingenginejava.domain.RejectReason;
import com.github.h3yow.ordermatchingenginejava.domain.Trade;
import java.util.List;

/** 下单/撤单结果。 */
public class OrderResult {

    private final boolean accepted;
    private final RejectReason rejectReason;
    private final String orderId;
    private final List<Trade> trades;

    private OrderResult(boolean accepted, RejectReason rejectReason,
                        String orderId, List<Trade> trades) {
        this.accepted = accepted;
        this.rejectReason = rejectReason;
        this.orderId = orderId;
        this.trades = trades;
    }

    public static OrderResult accepted(String orderId, List<Trade> trades) {
        return new OrderResult(true, null, orderId, trades);
    }

    public static OrderResult rejected(RejectReason reason) {
        return new OrderResult(false, reason, null, List.of());
    }

    public boolean isAccepted() { return accepted; }
    public RejectReason getRejectReason() { return rejectReason; }
    public String getOrderId() { return orderId; }
    public List<Trade> getTrades() { return trades; }
}
