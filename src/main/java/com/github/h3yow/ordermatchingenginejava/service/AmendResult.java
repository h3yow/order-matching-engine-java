package com.github.h3yow.ordermatchingenginejava.service;

import com.github.h3yow.ordermatchingenginejava.domain.Order;
import com.github.h3yow.ordermatchingenginejava.domain.RejectReason;
import java.util.List;

/**
 * 改单结果。
 *
 * @param order      改单后的订单（幂等命中时返回当前订单状态）
 * @param idempotent true 表示命中 clientAmendId 幂等，本次请求未重复改单/重复调整冻结
 * @param fills      本次改单触发即时撮合产生的成交 id 列表（幂等命中时为空）
 */
public record AmendResult(Order order, boolean idempotent, RejectReason rejectReason, List<Long> fillIds) {
}
