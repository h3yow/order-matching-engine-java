package com.github.h3yow.ordermatchingenginejava.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * 账户：资金 + 各标的持仓。
 *
 * <p>守恒约定：
 * <ul>
 *   <li>买挂单冻结资金：cash.available -= notional；成交时 cash -= notional 并回补冻结；</li>
 *   <li>卖挂单冻结持仓：position.available -= qty；成交时 position.qty -= qty 并回补冻结。</li>
 * </ul>
 * 任意时刻 cash.available &ge; 0、position.available &ge; 0 且
 * position.available &le; position.quantity。</p>
 */
public final class Account {

    private String accountId;
    private long cash;
    private long availableCash;
    private final Map<String, Position> positions = new HashMap<>();

    public Account() {
    }

    public Account(String accountId, long cash) {
        this.accountId = accountId;
        this.cash = cash;
        this.availableCash = cash;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public long getCash() {
        return cash;
    }

    public void setCash(long cash) {
        this.cash = cash;
    }

    public long getAvailableCash() {
        return availableCash;
    }

    public void setAvailableCash(long availableCash) {
        this.availableCash = availableCash;
    }

    public Map<String, Position> getPositions() {
        return positions;
    }

    public Position positionOf(String symbol) {
        return positions.computeIfAbsent(symbol, s -> new Position(0, 0));
    }
}
