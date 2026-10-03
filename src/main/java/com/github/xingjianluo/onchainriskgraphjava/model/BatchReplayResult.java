package com.github.xingjianluo.onchainriskgraphjava.model;

import java.util.List;

/**
 * 批量历史回放结果：点位顺序与请求顺序严格一致。
 *
 * @param requested 请求点位数
 * @param points    每个点位的回放结果（顺序与请求一致）
 */
public record BatchReplayResult(int requested, List<PointResult> points) {

    /**
     * 单个点位的回放结果。
     *
     * @param point       回放点位
     * @param conclusions 该点位结论：单地址时长度为 1，全量时按地址字典序
     */
    public record PointResult(ReplayPoint point, List<RiskConclusion> conclusions) {
    }
}
