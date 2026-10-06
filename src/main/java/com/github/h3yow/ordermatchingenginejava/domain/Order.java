package com.github.h3yow.ordermatchingenginejava.domain;

/**
 * 订单实体（可变状态，所有状态迁移均在引擎串行锁内完成）。
 *
 * <p>价格与数量均使用 long 定点数表示：金额单位为最小货币单位的 1/10000，
 * 数量单位为标的最小交易单位的 1/10000，避免浮点误差。</p>
 */
public final class Order {

    private long orderId;
    private String clientOrderId;
    private String accountId;
    private String symbol;
    private Side side;
    private OrderType type;
    /** 限价单价格（MARKET 单为 0）。 */
    private long limitPrice;
    private long quantity;
    private long filledQty;
    private OrderStatus status;
    /** 风控拒绝原因（status=REJECTED 时有值）。 */
    private RejectReason rejectReason;
    private long createdAt;
    /** 单调递增的接收序号，决定同一价格下的时间优先。 */
    private long seq;
    /** 最近一次生效改单的客户端幂等键（未改过为 null），随 ORDER_AMENDED 事件落盘。 */
    private String amendId;
    /**
     * 当前这笔冻结使用的单价（BUY：冻结资金的最坏单价；SELL 不使用）。
     * 限价单等于限价；市价单为下单时参考价上浮保护带。冻结/释放一律按
     * remainingQty*frozenPrice 重算，保证与重放口径逐字段一致。
     */
    private long frozenPrice;

    public Order() {
    }

    public long getOrderId() {
        return orderId;
    }

    public void setOrderId(long orderId) {
        this.orderId = orderId;
    }

    public String getClientOrderId() {
        return clientOrderId;
    }

    public void setClientOrderId(String clientOrderId) {
        this.clientOrderId = clientOrderId;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public Side getSide() {
        return side;
    }

    public void setSide(Side side) {
        this.side = side;
    }

    public OrderType getType() {
        return type;
    }

    public void setType(OrderType type) {
        this.type = type;
    }

    public long getLimitPrice() {
        return limitPrice;
    }

    public void setLimitPrice(long limitPrice) {
        this.limitPrice = limitPrice;
    }

    public long getQuantity() {
        return quantity;
    }

    public void setQuantity(long quantity) {
        this.quantity = quantity;
    }

    public long getFilledQty() {
        return filledQty;
    }

    public void setFilledQty(long filledQty) {
        this.filledQty = filledQty;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public RejectReason getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(RejectReason rejectReason) {
        this.rejectReason = rejectReason;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public String getAmendId() {
        return amendId;
    }

    public void setAmendId(String amendId) {
        this.amendId = amendId;
    }

    public long getFrozenPrice() {
        return frozenPrice;
    }

    public void setFrozenPrice(long frozenPrice) {
        this.frozenPrice = frozenPrice;
    }

    /** 剩余未成交数量。 */
    public long remainingQty() {
        return quantity - filledQty;
    }

    /** 是否仍在订单簿中（含部分成交）。 */
    public boolean isLive() {
        return status == OrderStatus.NEW;
    }
}
