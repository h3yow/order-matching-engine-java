package com.github.h3yow.ordermatchingenginejava.domain;

/**
 * 成交记录。tradeId 全局唯一，用于重放去重（重复投递不重复结算）。
 */
public class Trade {

    private final String tradeId;
    private final String buyOrderId;
    private final String sellOrderId;
    private final String buyAccountId;
    private final String sellAccountId;
    private final long price;
    private final long quantity;
    private final long sequence;

    public Trade(String tradeId, String buyOrderId, String sellOrderId,
                 String buyAccountId, String sellAccountId,
                 long price, long quantity, long sequence) {
        this.tradeId = tradeId;
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.buyAccountId = buyAccountId;
        this.sellAccountId = sellAccountId;
        this.price = price;
        this.quantity = quantity;
        this.sequence = sequence;
    }

    public String getTradeId() { return tradeId; }
    public String getBuyOrderId() { return buyOrderId; }
    public String getSellOrderId() { return sellOrderId; }
    public String getBuyAccountId() { return buyAccountId; }
    public String getSellAccountId() { return sellAccountId; }
    public long getPrice() { return price; }
    public long getQuantity() { return quantity; }
    public long getSequence() { return sequence; }

    @Override
    public String toString() {
        return "Trade{id=" + tradeId + ", buy=" + buyOrderId + ", sell=" + sellOrderId
                + ", price=" + price + ", qty=" + quantity + ", seq=" + sequence + "}";
    }
}
