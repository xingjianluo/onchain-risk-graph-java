package com.github.xingjianluo.onchainriskgraphjava.model;

/**
 * 同一地址在同一时间点、两个规则版本下的判定差异（单条）。
 *
 * @param address     被比较的地址
 * @param riskyA      版本 A 下是否高风险
 * @param riskyB      版本 B 下是否高风险
 * @param scoreA      版本 A 下的聚合分
 * @param scoreB      版本 B 下的聚合分
 * @param scoreDelta  scoreB - scoreA
 * @param conclusionA 版本 A 下的完整结论（含路径依据）
 * @param conclusionB 版本 B 下的完整结论（含路径依据）
 */
public record RuleVerdictChange(String address,
                                boolean riskyA, boolean riskyB,
                                double scoreA, double scoreB, double scoreDelta,
                                RiskConclusion conclusionA, RiskConclusion conclusionB) {
}
