package com.github.h3yow.ordermatchingenginejava.service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonAutoDetect;

/**
 * 账户快照（测试日志/接口诊断）：现金、可用现金、各标的持仓总量与可卖量。
 * positions 的 value 为长度 2 的数组：[总持仓, 可卖持仓]。
 * 提供基于值的 equals/hashCode/toString，便于测试中比较与日志输出。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class AccountSnapshot {

    private final String accountId;
    private final long cash;
    private final long availableCash;
    private final Map<String, long[]> positions;

    public AccountSnapshot(String accountId, long cash, long availableCash,
                          Map<String, long[]> positions) {
        this.accountId = accountId;
        this.cash = cash;
        this.availableCash = availableCash;
        this.positions = positions;
    }

    public String accountId() {
        return accountId;
    }

    public long cash() {
        return cash;
    }

    public long availableCash() {
        return availableCash;
    }

    public Map<String, long[]> positions() {
        return positions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AccountSnapshot other)) {
            return false;
        }
        return cash == other.cash
                && availableCash == other.availableCash
                && Objects.equals(accountId, other.accountId)
                && positionsDeepEqual(positions, other.positions);
    }

    private static boolean positionsDeepEqual(Map<String, long[]> a, Map<String, long[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<String, long[]> e : a.entrySet()) {
            if (!Arrays.equals(e.getValue(), b.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(accountId, cash, availableCash);
        for (Map.Entry<String, long[]> e : positions.entrySet()) {
            result = 31 * result + Objects.hash(e.getKey(), Arrays.hashCode(e.getValue()));
        }
        return result;
    }

    @Override
    public String toString() {
        Map<String, String> readable = new LinkedHashMap<>();
        positions.forEach((s, v) -> readable.put(s, "qty=" + v[0] + ",avail=" + v[1]));
        return "AccountSnapshot{account=" + accountId
                + ", cash=" + cash + ", availableCash=" + availableCash
                + ", positions=" + readable + "}";
    }
}
