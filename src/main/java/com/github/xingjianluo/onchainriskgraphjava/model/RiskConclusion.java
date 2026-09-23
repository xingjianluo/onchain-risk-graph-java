package com.github.xingjianluo.onchainriskgraphjava.model;

import java.util.List;

/**
 * 某地址在某时间点的风险结论（快照计算结果，不可变）。
 *
 * @param address       被评估地址
 * @param asOf          快照时间点
 * @param riskScore     聚合后的总分（noisy-OR：1 - Π(1-贡献)）
 * @param risky         是否达到阈值
 * @param ruleVersion   判定所依据的规则版本
 * @param riskThreshold 判定阈值
 * @param contributions 各风险源的传播路径与贡献，按贡献降序
 */
public record RiskConclusion(String address, long asOf, double riskScore, boolean risky,
                             int ruleVersion, double riskThreshold,
                             List<RiskPath> contributions) {
}
