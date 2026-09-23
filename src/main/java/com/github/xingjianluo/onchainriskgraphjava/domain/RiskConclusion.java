package com.github.xingjianluo.onchainriskgraphjava.domain;

import java.util.List;

/**
 * 某地址在某个时点、某版规则下的风险结论。
 *
 * @param address     被评估地址
 * @param score       聚合后的总风险分 [0,1]
 * @param risky       是否达到/超过风险阈值
 * @param asOf        快照时点（epoch millis），结论只反映该时点之前可见的数据
 * @param ruleVersion 生成该结论所用规则的版本
 * @param threshold   当时生效的风险阈值
 * @param paths       全部贡献路径（按贡献降序），用于回溯与解释
 */
public record RiskConclusion(String address,
                             double score,
                             boolean risky,
                             long asOf,
                             String ruleVersion,
                             double threshold,
                             List<RiskPath> paths) {
}
