package com.github.xingjianluo.onchainriskgraphjava.model;

/**
 * 风险规则的一个不可变版本。
 *
 * @param version       版本号，从 1 递增
 * @param effectiveAt   生效时间点（含），各版本必须严格递增
 * @param maxHops       最大传播跳数（0 表示只认风险源本身）
 * @param decay         每跳衰减系数，(0,1]
 * @param riskThreshold 高风险判定阈值，(0,1]
 */
public record RuleVersion(int version, long effectiveAt, int maxHops,
                          double decay, double riskThreshold) {
}
