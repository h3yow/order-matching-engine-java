package com.github.h3yow.ordermatchingenginejava.domain;

/**
 * 单一标的的持仓：总持仓与可卖持仓（挂卖单会冻结对应数量）。
 * 数量为定点 long，正数表示多头。
 */
public final class Position {

    private long quantity;
    private long available;

    public Position() {
    }

    public Position(long quantity, long available) {
        this.quantity = quantity;
        this.available = available;
    }

    public long getQuantity() {
        return quantity;
    }

    public void setQuantity(long quantity) {
        this.quantity = quantity;
    }

    public long getAvailable() {
        return available;
    }

    public void setAvailable(long available) {
        this.available = available;
    }
}
