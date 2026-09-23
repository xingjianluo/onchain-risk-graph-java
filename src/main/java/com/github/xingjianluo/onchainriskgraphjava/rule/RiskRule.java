package com.github.xingjianluo.onchainriskgraphjava.rule;

/**
 * 风险传播规则的一个版本。
 *
 * @param version        版本标识（唯一）
 * @param maxHops        最大传播跳数
 * @param decay          每跳衰减因子 (0,1]
 * @param threshold      判定高风险的阈值 [0,1]
 * @param effectiveFrom  版本生效起点（epoch millis，含）
 * @param note           说明
 */
public record RiskRule(String version,
                       int maxHops,
                       double decay,
                       double threshold,
                       long effectiveFrom,
                       String note) {
}
