package com.github.h3yow.ordermatchingenginejava.account;

/**
 * 账户：现金与持仓均分为可用/冻结两部分。
 * 不变量：available >= 0 且 frozen >= 0。
 */
public class Account {

    private final String accountId;
    private long cashAvailable;
    private long cashFrozen;
    private long positionAvailable;
    private long positionFrozen;

    public Account(String accountId, long cashAvailable, long positionAvailable) {
        this.accountId = accountId;
        this.cashAvailable = cashAvailable;
        this.positionAvailable = positionAvailable;
    }

    public String getAccountId() { return accountId; }
    public long getCashAvailable() { return cashAvailable; }
    public long getCashFrozen() { return cashFrozen; }
    public long getPositionAvailable() { return positionAvailable; }
    public long getPositionFrozen() { return positionFrozen; }

    /** 买入冻结资金。调用方须先校验余额充足。 */
    public void freezeCash(long amount) {
        if (amount > cashAvailable) {
            throw new IllegalStateException("freezeCash exceeds available: " + this);
        }
        cashAvailable -= amount;
        cashFrozen += amount;
    }

    /** 卖出冻结持仓。 */
    public void freezePosition(long qty) {
        if (qty > positionAvailable) {
            throw new IllegalStateException("freezePosition exceeds available: " + this);
        }
        positionAvailable -= qty;
        positionFrozen += qty;
    }

    /** 撤单/未成交部分解冻资金。 */
    public void unfreezeCash(long amount) {
        cashFrozen -= amount;
        cashAvailable += amount;
    }

    /** 撤单解冻持仓。 */
    public void unfreezePosition(long qty) {
        positionFrozen -= qty;
        positionAvailable += qty;
    }

    /** 买入成交结算：冻结资金按实际成交价扣减，差额退回可用，持仓增加。 */
    public void settleBuy(long frozenToConsume, long actualCost, long qty) {
        cashFrozen -= frozenToConsume;
        cashAvailable += (frozenToConsume - actualCost);
        positionAvailable += qty;
    }

    /** 卖出成交结算：冻结持仓扣减，资金增加。 */
    public void settleSell(long qty, long proceeds) {
        positionFrozen -= qty;
        cashAvailable += proceeds;
    }

    public String snapshot() {
        return "Account{" + accountId
                + ", cashAvail=" + cashAvailable + ", cashFrozen=" + cashFrozen
                + ", posAvail=" + positionAvailable + ", posFrozen=" + positionFrozen + "}";
    }

    @Override
    public String toString() { return snapshot(); }
}
