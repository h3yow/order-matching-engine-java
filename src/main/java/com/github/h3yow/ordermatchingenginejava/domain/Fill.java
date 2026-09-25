package com.github.h3yow.ordermatchingenginejava.domain;

/**
 * 成交流水（不可变）：一条记录代表“归属账户一侧”的一次成交。
 *
 * <p>一次撮合产生两条 Fill（taker 一条、maker 一条），共享同一个 fillId，
 * 以 {@code fillId + ownerAccountId} 作为幂等键：重复投递同一成交事件不会重复结算。</p>
 *
 * @param ownerAccountId 该流水归属账户（资金/持仓结算到该账户）
 * @param ownerSide      归属方成交方向（maker 方向与 taker 相反）
 * @param roleTaker      归属方是否为主动成交方
 * @param takerOrderId   主动单 id
 * @param makerOrderId   挂单 id
 */
public record Fill(
        long fillId,
        long matchId,
        long price,
        long quantity,
        String symbol,
        String ownerAccountId,
        Side ownerSide,
        boolean roleTaker,
        long ownerOrderId,
        long takerOrderId,
        long makerOrderId,
        long timestamp
) {
}
