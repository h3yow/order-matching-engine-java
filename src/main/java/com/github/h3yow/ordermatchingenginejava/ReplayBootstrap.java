package com.github.h3yow.ordermatchingenginejava;

import com.github.h3yow.ordermatchingenginejava.engine.MatchingEngine;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 服务启动完成后依据本地流水重放，恢复订单簿、账户与成交状态。 */
@Component
public class ReplayBootstrap {

    private final MatchingEngine engine;

    public ReplayBootstrap(MatchingEngine engine) {
        this.engine = engine;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        engine.replay();
    }
}
