package com.github.h3yow.ordermatchingenginejava.service;

import com.github.h3yow.ordermatchingenginejava.domain.Order;
import com.github.h3yow.ordermatchingenginejava.domain.RejectReason;

/**
 * 撤单结果：成功返回订单（状态 CANCELED，可能有部分成交），失败原因见 rejectReason。
 */
public record CancelResult(Order order, boolean accepted, RejectReason rejectReason, long canceledQuantity) {
}
