package com.github.xingjianluo.onchainriskgraphjava.domain;

/**
 * 对地址的人工/外部高风险标记，是风险传播的源头。
 *
 * @param address   被标记地址
 * @param baseScore 初始风险分 [0,1]，1 表示确认高风险
 * @param reason    标记理由（可解释性来源）
 * @param timestamp 标记生效时间（epoch millis）
 */
public record RiskLabel(String address,
                        double baseScore,
                        String reason,
                        long timestamp) {
}
