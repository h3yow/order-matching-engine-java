package com.github.h3yow.ordermatchingenginejava.risk;

import com.github.h3yow.ordermatchingenginejava.config.TradingProperties;
import com.github.h3yow.ordermatchingenginejava.domain.Account;
import com.github.h3yow.ordermatchingenginejava.domain.Order;
import com.github.h3yow.ordermatchingenginejava.domain.OrderType;
import com.github.h3yow.ordermatchingenginejava.domain.Position;
import com.github.h3yow.ordermatchingenginejava.domain.RejectReason;
import com.github.h3yow.ordermatchingenginejava.domain.Side;
import com.github.h3yow.ordermatchingenginejava.domain.TradingException;
import org.springframework.stereotype.Component;

/**
 * 下单前风控（纯读，绝不修改任何状态）：
 * <ol>
 *   <li>数量必须为正；限价单价格必须为正；</li>
 *   <li>单笔数量不得超过 maxOrderQuantity；</li>
 *   <li>价格合理性：限价不得偏离参考价超过 maxDeviationBps（无参考价时跳过）；</li>
 *   <li>买单按最坏成本冻结：限价单用 limitPrice，市价单用参考价上浮保护带；
 *       冻结后可用现金不得 &lt; minAvailableCash（即不得为负）；</li>
 *   <li>卖单冻结数量不得超过可卖持仓；成交后净持仓绝对值不得超过 maxPosition。</li>
 * </ol>
 *
 * <p>限价卖单涉及持仓减少，只会让持仓更负，因此需要检查 |quantity - position|。</p>
 */
@Component
public class RiskValidator {

    private final TradingProperties properties;

    public RiskValidator(TradingProperties properties) {
        this.properties = properties;
    }

    public void validate(Account account, Order order, long referencePrice) {
        if (order.getQuantity() <= 0) {
            throw new TradingException(RejectReason.INVALID_QUANTITY,
                    "quantity must be positive: " + order.getQuantity());
        }
        if (properties.getMaxOrderQuantity() > 0
                && order.getQuantity() > properties.getMaxOrderQuantity()) {
            throw new TradingException(RejectReason.QUANTITY_EXCEEDS_LIMIT,
                    "quantity " + order.getQuantity() + " exceeds max "
                            + properties.getMaxOrderQuantity());
        }
        boolean limit = order.getType() == OrderType.LIMIT;
        if (limit && order.getLimitPrice() <= 0) {
            throw new TradingException(RejectReason.INVALID_PRICE,
                    "limit price must be positive: " + order.getLimitPrice());
        }
        if (limit && referencePrice > 0 && !withinBand(order.getLimitPrice(), referencePrice)) {
            throw new TradingException(RejectReason.PRICE_OUT_OF_BAND,
                    "price " + order.getLimitPrice() + " too far from reference " + referencePrice);
        }

        long qty = order.getQuantity();
        if (order.getSide() == Side.BUY) {
            long worstPrice = limit ? order.getLimitPrice() : marketWorstBuyPrice(referencePrice);
            if (worstPrice <= 0) {
                throw new TradingException(RejectReason.INVALID_PRICE,
                        "market buy requires a positive reference price for protection");
            }
            long needCash = qty * worstPrice / SCALE;
            long projected = account.getAvailableCash() - needCash;
            if (projected < properties.getMinAvailableCash()) {
                throw new TradingException(RejectReason.INSUFFICIENT_AVAILABLE,
                        "available cash " + account.getAvailableCash()
                                + " cannot reserve " + needCash);
            }
        } else {
            Position pos = account.positionOf(order.getSymbol());
            if (qty > pos.getAvailable()) {
                throw new TradingException(RejectReason.INSUFFICIENT_AVAILABLE,
                        "available position " + pos.getAvailable() + " < sell qty " + qty);
            }
            long projectedPosition = Math.abs(pos.getQuantity() - qty);
            if (projectedPosition > properties.getMaxPosition()) {
                throw new TradingException(RejectReason.POSITION_LIMIT_EXCEEDED,
                        "projected |position| " + projectedPosition
                                + " exceeds max " + properties.getMaxPosition());
            }
        }
    }

    /** 价格数量定点精度：1 个单位 = 0.0001。 */
    public static final long SCALE = 10000L;

    private boolean withinBand(long price, long referencePrice) {
        long deviation = Math.abs(price - referencePrice) * 10000L / referencePrice;
        return deviation <= properties.getMaxDeviationBps();
    }

    private long marketWorstBuyPrice(long referencePrice) {
        if (referencePrice <= 0) {
            return 0;
        }
        return referencePrice * (10000L + properties.getMaxDeviationBps()) / 10000L;
    }
}
