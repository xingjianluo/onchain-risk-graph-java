package com.github.xingjianluo.onchainriskgraphjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 风险规则相关配置项（仅在启动时生成初始规则版本，运行期变更走规则发布接口）。
 */
@Component
@ConfigurationProperties(prefix = "risk")
public class RiskProperties {

    /** 初始规则生效时间点（毫秒） */
    private long initialEffectiveAt = 0L;
    /** 最大传播跳数 */
    private int maxHops = 3;
    /** 每跳衰减系数 */
    private double decay = 0.5d;
    /** 高风险判定阈值 */
    private double riskThreshold = 0.5d;

    public long getInitialEffectiveAt() { return initialEffectiveAt; }
    public void setInitialEffectiveAt(long v) { this.initialEffectiveAt = v; }
    public int getMaxHops() { return maxHops; }
    public void setMaxHops(int v) { this.maxHops = v; }
    public double getDecay() { return decay; }
    public void setDecay(double v) { this.decay = v; }
    public double getRiskThreshold() { return riskThreshold; }
    public void setRiskThreshold(double v) { this.riskThreshold = v; }
}
