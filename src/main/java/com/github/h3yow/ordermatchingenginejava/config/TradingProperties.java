package com.github.h3yow.ordermatchingenginejava.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 撮合与风控相关配置（前缀 trading.*）。
 * 金额/数量单位为定点 long：1 = 0.0001。
 */
@ConfigurationProperties(prefix = "trading")
public class TradingProperties {

    /** 单笔订单最大数量（0 表示不限制）。 */
    private long maxOrderQuantity = 1_000_0000L;

    /** 账户可用余额下限（始终为 0），下单后不得为负。 */
    private long minAvailableCash = 0L;

    /** 单标的净持仓上限（绝对值）。 */
    private long maxPosition = 10_000_0000L;

    /** 市价单/价格保护：成交价相对参考价允许的最大滑点（万分之比，如 500 = 5%）。 */
    private long maxDeviationBps = 500L;

    /** 事件流水（订单/成交/撤单）持久化目录。 */
    private String eventLogDir = "data/trading-log";

    /** 初始种子账户：accountId -> 初始资金。 */
    private Map<String, Long> seedAccounts = new LinkedHashMap<>();

    /** 初始种子持仓：key=accountId__symbol，value=数量。 */
    private Map<String, Long> seedPositions = new LinkedHashMap<>();

    public long getMaxOrderQuantity() {
        return maxOrderQuantity;
    }

    public void setMaxOrderQuantity(long maxOrderQuantity) {
        this.maxOrderQuantity = maxOrderQuantity;
    }

    public long getMinAvailableCash() {
        return minAvailableCash;
    }

    public void setMinAvailableCash(long minAvailableCash) {
        this.minAvailableCash = minAvailableCash;
    }

    public long getMaxPosition() {
        return maxPosition;
    }

    public void setMaxPosition(long maxPosition) {
        this.maxPosition = maxPosition;
    }

    public long getMaxDeviationBps() {
        return maxDeviationBps;
    }

    public void setMaxDeviationBps(long maxDeviationBps) {
        this.maxDeviationBps = maxDeviationBps;
    }

    public String getEventLogDir() {
        return eventLogDir;
    }

    public void setEventLogDir(String eventLogDir) {
        this.eventLogDir = eventLogDir;
    }

    public Map<String, Long> getSeedAccounts() {
        return seedAccounts;
    }

    public void setSeedAccounts(Map<String, Long> seedAccounts) {
        this.seedAccounts = seedAccounts;
    }

    public Map<String, Long> getSeedPositions() {
        return seedPositions;
    }

    public void setSeedPositions(Map<String, Long> seedPositions) {
        this.seedPositions = seedPositions;
    }
}
