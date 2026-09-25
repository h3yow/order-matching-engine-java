package com.github.h3yow.ordermatchingenginejava.engine;

import com.github.h3yow.ordermatchingenginejava.domain.Order;

/**
 * 一次撮合结果：taker（主动单）与 maker（挂单）、成交价与成交数量。
 */
public record Match(Order taker, Order maker, long price, long quantity) {
}
