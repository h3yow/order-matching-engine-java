package com.github.h3yow.ordermatchingenginejava.api;

import com.github.h3yow.ordermatchingenginejava.domain.Order;
import com.github.h3yow.ordermatchingenginejava.domain.OrderStatus;
import com.github.h3yow.ordermatchingenginejava.domain.Side;
import com.github.h3yow.ordermatchingenginejava.engine.OrderBook;
import com.github.h3yow.ordermatchingenginejava.service.AccountSnapshot;
import com.github.h3yow.ordermatchingenginejava.service.CancelResult;
import com.github.h3yow.ordermatchingenginejava.service.OrderResult;
import com.github.h3yow.ordermatchingenginejava.service.TradingService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST 接口：下单、撤单、外部成交投递、查询。 */
@RestController
@RequestMapping("/api/trading")
public class TradingController {

    private final TradingService tradingService;

    public TradingController(TradingService tradingService) {
        this.tradingService = tradingService;
    }

    public record SubmitOrderRequest(
            String clientOrderId,
            String accountId,
            String symbol,
            String side,
            String type,
            long price,
            long quantity
    ) {
    }

    public record SubmitOrderResponse(
            boolean accepted,
            boolean idempotent,
            long orderId,
            String status,
            String rejectReason,
            long filledQuantity,
            long remainingQuantity,
            List<Long> fillIds
    ) {
    }

    /**
     * 外部成交事件投递（单条账户侧流水）。同一 fillId+ownerAccountId 重复投递返回 duplicate=true。
     */
    public record DeliverFillRequest(
            long fillId,
            long matchId,
            long price,
            long quantity,
            String symbol,
            String ownerAccountId,
            String ownerSide,
            boolean roleTaker,
            long ownerOrderId,
            long takerOrderId,
            long makerOrderId,
            long timestamp
    ) {
    }

    public record DeliverFillResponse(boolean applied, boolean duplicate) {
    }

    @PostMapping("/orders")
    public SubmitOrderResponse submit(@RequestBody SubmitOrderRequest req) {
        OrderResult result = tradingService.submitOrder(
                req.clientOrderId(), req.accountId(), req.symbol(),
                req.side(), req.type(), req.price(), req.quantity());
        Order o = result.order();
        return new SubmitOrderResponse(
                o != null && o.getStatus() != OrderStatus.REJECTED,
                result.idempotent(),
                o == null ? 0 : o.getOrderId(),
                o == null ? null : o.getStatus().name(),
                result.rejectReason() == null ? null : result.rejectReason().name(),
                o == null ? 0 : o.getFilledQty(),
                o == null ? 0 : o.remainingQty(),
                result.fillIds());
    }

    @PostMapping("/orders/{orderId}/cancel")
    public CancelResult cancel(@PathVariable long orderId) {
        return tradingService.cancel(orderId);
    }

    /**
     * 改单请求：price/quantity 传 null 表示该项不变；quantity 为新的订单总数量（含已成交）。
     * clientAmendId 为幂等键：同一 orderId+clientAmendId 重复提交只生效一次。
     */
    public record AmendOrderRequest(
            String clientAmendId,
            Long price,
            Long quantity
    ) {
    }

    @PostMapping("/orders/{orderId}/amend")
    public SubmitOrderResponse amend(@PathVariable long orderId,
                                     @RequestBody AmendOrderRequest req) {
        OrderResult result = tradingService.amendOrder(
                orderId, req.clientAmendId(), req.price(), req.quantity());
        Order o = result.order();
        return new SubmitOrderResponse(
                o != null && o.getStatus() != OrderStatus.REJECTED,
                result.idempotent(),
                o == null ? 0 : o.getOrderId(),
                o == null ? null : o.getStatus().name(),
                result.rejectReason() == null ? null : result.rejectReason().name(),
                o == null ? 0 : o.getFilledQty(),
                o == null ? 0 : o.remainingQty(),
                result.fillIds());
    }

    @PostMapping("/fills/deliver")
    public DeliverFillResponse deliver(@RequestBody DeliverFillRequest req) {
        boolean applied = tradingService.deliverFill(
                req.fillId(), req.matchId(), req.price(), req.quantity(),
                req.symbol(), req.ownerAccountId(), Side.valueOf(req.ownerSide().toUpperCase()),
                req.roleTaker(), req.ownerOrderId(), req.takerOrderId(), req.makerOrderId(),
                req.timestamp());
        return new DeliverFillResponse(applied, !applied);
    }

    @GetMapping("/orders/{orderId}")
    public Order order(@PathVariable long orderId) {
        return tradingService.getOrder(orderId);
    }

    @GetMapping("/accounts/{accountId}")
    public AccountSnapshot account(@PathVariable String accountId) {
        return tradingService.snapshot(accountId);
    }

    public record OrderBookResponse(String symbol, List<Order> bids, List<Order> asks) {
    }

    @GetMapping("/symbols/{symbol}/book")
    public OrderBookResponse book(@PathVariable String symbol) {
        OrderBook book = tradingService.orderBook(symbol);
        if (book == null) {
            return new OrderBookResponse(symbol, List.of(), List.of());
        }
        return new OrderBookResponse(symbol, book.bidsSnapshot(), book.asksSnapshot());
    }
}
