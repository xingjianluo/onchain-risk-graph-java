package com.github.xingjianluo.onchainriskgraphjava.model;

/**
 * 同一时间点、同一地址在两个规则版本下的分值变化。
 *
 * @param address     地址
 * @param baseScore   基准版本下的分值
 * @param targetScore 目标版本下的分值
 * @param delta       分值差（targetScore - baseScore）
 * @param baseRisky   基准版本下是否高风险
 * @param targetRisky 目标版本下是否高风险
 */
public record ScoreChange(String address, double baseScore, double targetScore,
                          double delta, boolean baseRisky, boolean targetRisky) {
}
