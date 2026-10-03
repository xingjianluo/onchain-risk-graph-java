package com.github.xingjianluo.onchainriskgraphjava.model;

import java.util.List;

/**
 * 同一时间点、两个已发布规则版本的判定差异。
 *
 * @param asOf          对比的时间点
 * @param baseVersion   基准规则版本号
 * @param targetVersion 目标规则版本号
 * @param becameRisky   从正常变为高风险的地址（含分值变化）
 * @param cleared       从高风险解除为正常的地址（含分值变化）
 * @param scoreChanges  所有分值有变化的地址（含上述两类），按地址字典序
 */
public record RuleDiffResult(long asOf, int baseVersion, int targetVersion,
                             List<ScoreChange> becameRisky,
                             List<ScoreChange> cleared,
                             List<ScoreChange> scoreChanges) {
}
