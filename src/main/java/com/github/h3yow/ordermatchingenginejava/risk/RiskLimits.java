package com.github.h3yow.ordermatchingenginejava.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 风控阈值配置。 */
@Component
@ConfigurationProperties(prefix = "risk")
public class RiskLimits {

    /** 单笔最大数量。 */
    private long maxOrderQuantity = 100_000;
    /** 价格合理性：相对参考价允许的最大偏离比例（万分比）。 */
    private long maxPriceDeviationBps = 2_000;
    /** 价格合理性参考价；无成交时使用的基准价。 */
    private long referencePrice = 10_000;
    /** 账户默认初始资金。 */
    private long defaultCash = 10_000_000;
    /** 账户默认初始持仓。 */
    private long defaultPosition = 1_000_000;

    public long getMaxOrderQuantity() { return maxOrderQuantity; }
    public void setMaxOrderQuantity(long v) { this.maxOrderQuantity = v; }
    public long getMaxPriceDeviationBps() { return maxPriceDeviationBps; }
    public void setMaxPriceDeviationBps(long v) { this.maxPriceDeviationBps = v; }
    public long getReferencePrice() { return referencePrice; }
    public void setReferencePrice(long v) { this.referencePrice = v; }
    public long getDefaultCash() { return defaultCash; }
    public void setDefaultCash(long v) { this.defaultCash = v; }
    public long getDefaultPosition() { return defaultPosition; }
    public void setDefaultPosition(long v) { this.defaultPosition = v; }
}
