package com.github.h3yow.ordermatchingenginejava.domain;

/**
 * 订单。价格为整数最小报价单位（分），数量为整数股数，避免浮点误差。
 */
public class Order {

    private final String orderId;
    private final String clientOrderId;
    private final String accountId;
    private final Side side;
    private final OrderType type;
    /** 限价单价格；市价单为 null。 */
    private final Long price;
    private final long quantity;
    private long filledQuantity;
    private OrderStatus status;
    /** 撮合序号，保证时间优先可解释。 */
    private long sequence;

    public Order(String orderId, String clientOrderId, String accountId,
                 Side side, OrderType type, Long price, long quantity) {
        this.orderId = orderId;
        this.clientOrderId = clientOrderId;
        this.accountId = accountId;
        this.side = side;
        this.type = type;
        this.price = price;
        this.quantity = quantity;
        this.filledQuantity = 0;
        this.status = OrderStatus.OPEN;
    }

    public String getOrderId() { return orderId; }
    public String getClientOrderId() { return clientOrderId; }
    public String getAccountId() { return accountId; }
    public Side getSide() { return side; }
    public OrderType getType() { return type; }
    public Long getPrice() { return price; }
    public long getQuantity() { return quantity; }
    public long getFilledQuantity() { return filledQuantity; }
    public long getRemainingQuantity() { return quantity - filledQuantity; }
    public OrderStatus getStatus() { return status; }
    public long getSequence() { return sequence; }

    public void setSequence(long sequence) { this.sequence = sequence; }

    public void addFill(long qty) {
        this.filledQuantity += qty;
        this.status = (filledQuantity >= quantity)
                ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    public void markCanceled() { this.status = OrderStatus.CANCELED; }
    public void markRejected() { this.status = OrderStatus.REJECTED; }

    public boolean isActive() {
        return status == OrderStatus.OPEN || status == OrderStatus.PARTIALLY_FILLED;
    }

    @Override
    public String toString() {
        return "Order{id=" + orderId + ", clientId=" + clientOrderId
                + ", acct=" + accountId + ", " + side + " " + type
                + ", price=" + price + ", qty=" + quantity
                + ", filled=" + filledQuantity + ", status=" + status
                + ", seq=" + sequence + "}";
    }
}
