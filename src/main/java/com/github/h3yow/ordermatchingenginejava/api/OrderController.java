package com.github.h3yow.ordermatchingenginejava.api;

import com.github.h3yow.ordermatchingenginejava.account.Account;
import com.github.h3yow.ordermatchingenginejava.account.AccountService;
import com.github.h3yow.ordermatchingenginejava.domain.Order;
import com.github.h3yow.ordermatchingenginejava.domain.OrderType;
import com.github.h3yow.ordermatchingenginejava.domain.Side;
import com.github.h3yow.ordermatchingenginejava.engine.MatchingEngine;
import com.github.h3yow.ordermatchingenginejava.engine.OrderResult;
import org.springframework.web.bind.annotation.*;

/** 下单/撤单/查询 REST 接口。 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final MatchingEngine engine;
    private final AccountService accountService;

    public OrderController(MatchingEngine engine, AccountService accountService) {
        this.engine = engine;
        this.accountService = accountService;
    }

    public record SubmitRequest(String clientOrderId, String accountId,
                                Side side, OrderType type, Long price, long quantity) {}

    @PostMapping
    public OrderResult submit(@RequestBody SubmitRequest req) {
        return engine.submitOrder(req.clientOrderId(), req.accountId(), req.side(),
                req.type(), req.price(), req.quantity());
    }

    @DeleteMapping("/{orderId}")
    public OrderResult cancel(@PathVariable String orderId) {
        return engine.cancelOrder(orderId);
    }

    @GetMapping("/{orderId}")
    public Order getOrder(@PathVariable String orderId) {
        return engine.getOrder(orderId);
    }

    @GetMapping("/accounts/{accountId}")
    public Account getAccount(@PathVariable String accountId) {
        return accountService.getOrCreate(accountId);
    }
}
