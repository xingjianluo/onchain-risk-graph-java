package com.github.xingjianluo.onchainriskgraphjava.model;

/**
 * 批量历史回放中单个时间点的结果，顺序与请求中的时间点一一对应。
 *
 * @param asOf    回放的时间点
 * @param outcome 该时间点的评估结果（结论或可解释拒绝）
 */
public record ReplayItem(long asOf, QueryOutcome<RiskConclusion> outcome) {
}
