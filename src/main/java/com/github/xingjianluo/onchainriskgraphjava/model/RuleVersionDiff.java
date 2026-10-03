package com.github.xingjianluo.onchainriskgraphjava.model;

import java.util.List;

/**
 * 同一时间点两个规则版本的判定差异结果。
 *
 * @param asOf              快照时间点
 * @param versionA          版本 A 版本号
 * @param versionB          版本 B 版本号
 * @param thresholdA        版本 A 的判定阈值
 * @param thresholdB        版本 B 的判定阈值
 * @param becameRisky       A 正常 -> B 高风险 的地址
 * @param becameSafe        A 高风险 -> B 正常 的地址
 * @param scoreOnlyChanged  判定翻转之外、分值发生变化的地址
 * @param comparedAddresses 参与比较的地址总数
 * @param conclusionsA      版本 A 的全量结论（字典序）
 * @param conclusionsB      版本 B 的全量结论（字典序）
 */
public record RuleVersionDiff(long asOf,
                              int versionA, int versionB,
                              double thresholdA, double thresholdB,
                              List<RuleVerdictChange> becameRisky,
                              List<RuleVerdictChange> becameSafe,
                              List<RuleVerdictChange> scoreOnlyChanged,
                              int comparedAddresses,
                              List<RiskConclusion> conclusionsA,
                              List<RiskConclusion> conclusionsB) {
}
