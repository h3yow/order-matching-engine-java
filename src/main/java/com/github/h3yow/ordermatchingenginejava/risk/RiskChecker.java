package com.github.h3yow.ordermatchingenginejava.risk;

import com.github.h3yow.ordermatchingenginejava.domain.Order;
import com.github.h3yow.ordermatchingenginejava.domain.OrderType;
import com.github.h3yow.ordermatchingenginejava.domain.RejectReason;
import com.github.h3yow.ordermatchingenginejava.domain.Side;
import org.springframework.stereotype.Component;

/**
 * 下单前风控：单笔最大数量、可用余额/持仓上限、价格合理性。
 * 只读校验，不改变账户状态；冻结由引擎在校验通过后原子完成。
 */
@Component
public class RiskChecker {

    private final RiskLimits limits;

    public RiskChecker(RiskLimits limits) {
        this.limits = limits;
    }

    /**
     * 校验订单。返回 null 表示通过，否则返回可区分的拒绝原因。
     *
     * @param lastTradePrice 最近成交价（无成交时用参考价），用于价格合理性校验
     */
    public RejectReason check(Order order, long cashAvailable, long positionAvailable,
                              long lastTradePrice) {
        if (order.getQuantity() <= 0 || order.getQuantity() > limits.getMaxOrderQuantity()) {
            return RejectReason.EXCEED_MAX_ORDER_QUANTITY;
        }

        // 价格合理性：限价单价格不得偏离基准价超过阈值
        if (order.getType() == OrderType.LIMIT) {
            long price = order.getPrice();
            long maxDev = lastTradePrice * limits.getMaxPriceDeviationBps() / 10_000;
            if (price <= 0 || Math.abs(price - lastTradePrice) > maxDev) {
                return RejectReason.PRICE_OUT_OF_RANGE;
            }
        }

        // 资金/持仓上限
        if (order.getSide() == Side.BUY) {
            long required = requiredCash(order, lastTradePrice);
            if (required > cashAvailable) {
                return RejectReason.INSUFFICIENT_BALANCE;
            }
        } else {
            if (order.getQuantity() > positionAvailable) {
                return RejectReason.INSUFFICIENT_POSITION;
            }
        }
        return null;
    }

    /** 买入需冻结的资金：限价单按限价，市价单按基准价上浮容忍区间估算。 */
    public long requiredCash(Order order, long lastTradePrice) {
        long price = (order.getType() == OrderType.LIMIT)
                ? order.getPrice()
                : lastTradePrice * (10_000 + limits.getMaxPriceDeviationBps()) / 10_000;
        return price * order.getQuantity();
    }
}
