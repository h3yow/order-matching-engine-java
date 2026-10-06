package com.github.h3yow.ordermatchingenginejava.store;

/**
 * 改单流水（不可变）：一次成功的改单落一条记录。
 *
 * <p>重放时按 orderId 找到订单，把价格/数量/接收序号推进到改单后的值；
 * 已成交数量（filledQty）始终由 FILL 流水推进，本记录不携带。</p>
 *
 * @param orderId       被改订单 id
 * @param clientAmendId 客户端改单幂等键（同一订单内唯一，网络重试去重用）
 * @param limitPrice    改后限价（定点，1 = 0.0001）
 * @param quantity      改后订单总数量（含已成交部分；剩余 = quantity - filledQty）
 * @param seq           改后接收序号（改单失去原时间优先，按新序号排到队尾）
 * @param timestamp     改单时间（毫秒）
 */
public record Amend(
        long orderId,
        String clientAmendId,
        long limitPrice,
        long quantity,
        long seq,
        long timestamp
) {
}
