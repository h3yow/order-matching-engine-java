package com.github.h3yow.ordermatchingenginejava.engine;

import com.github.h3yow.ordermatchingenginejava.domain.Order;
import com.github.h3yow.ordermatchingenginejava.domain.OrderType;
import com.github.h3yow.ordermatchingenginejava.domain.Side;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;
import org.springframework.stereotype.Component;

/**
 * 撮合器：单标的、价格优先 + 时间优先。
 *
 * <p>规则：
 * <ul>
 *   <li>成交价取对手方挂单价（maker 价）；</li>
 *   <li>限价 BUY 仅与 &le; 限价的卖盘成交，限价 SELL 仅与 &ge; 限价的买盘成交；</li>
 *   <li>市价单不限价格，但每个价格仍需通过 priceGuard（参考价滑点保护），
 *       一旦某档不通过即停止（更深档位价格只会更差）；</li>
 *   <li>每次撮合更新双方 filledQty，完全成交的 maker 出簿；</li>
 *   <li>限价单有剩余则入簿等待；市价单有剩余不入簿（由服务层撤销剩余并释放冻结）。</li>
 * </ul>
 * 本类只产生 {@link Match}，资金/持仓结算由服务层完成。
 */
@Component
public class MatchingEngine {

    public List<Match> match(Order taker, OrderBook book, LongPredicate priceGuard) {
        List<Match> matches = new ArrayList<>();
        boolean buy = taker.getSide() == Side.BUY;

        while (taker.remainingQty() > 0) {
            Order maker = buy ? book.bestAsk() : book.bestBid();
            if (maker == null) {
                break;
            }
            long price = maker.getLimitPrice();
            if (!isCrossing(taker, price) || !priceGuard.test(price)) {
                break;
            }
            long qty = Math.min(taker.remainingQty(), maker.remainingQty());
            taker.setFilledQty(taker.getFilledQty() + qty);
            maker.setFilledQty(maker.getFilledQty() + qty);
            matches.add(new Match(taker, maker, price, qty));
            if (maker.remainingQty() == 0) {
                book.remove(maker);
            }
            if (taker.remainingQty() == 0) {
                // taker 若此前就在簿中（自成交或挂单再撮合场景）也需出簿，
                // 普通场景 taker 尚未入簿，remove 为空操作。
                book.remove(taker);
            }
        }

        if (taker.remainingQty() > 0 && taker.getType() == OrderType.LIMIT) {
            book.add(taker);
        }
        return matches;
    }

    /** 限价单的价格交叉判定；市价单与任何价格交叉。 */
    private boolean isCrossing(Order taker, long makerPrice) {
        if (taker.getType() == OrderType.MARKET) {
            return true;
        }
        return taker.getSide() == Side.BUY
                ? makerPrice <= taker.getLimitPrice()
                : makerPrice >= taker.getLimitPrice();
    }
}
