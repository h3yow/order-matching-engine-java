package com.github.h3yow.ordermatchingenginejava.service;

import com.github.h3yow.ordermatchingenginejava.domain.Order;
import com.github.h3yow.ordermatchingenginejava.domain.RejectReason;
import java.util.List;

/**
 * 下单结果。
 *
 * @param order      订单（重复提交时返回首次提交的同一个订单）
 * @param idempotent true 表示命中 clientOrderId 幂等，返回原结果且不会重复成交/扣款
 * @param fills      本次请求产生的成交 id 列表（幂等命中时为空，避免被误解为新成交）
 */
public record OrderResult(Order order, boolean idempotent, RejectReason rejectReason, List<Long> fillIds) {
}
