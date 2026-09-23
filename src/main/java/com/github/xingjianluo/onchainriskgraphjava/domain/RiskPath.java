package com.github.xingjianluo.onchainriskgraphjava.domain;

import java.util.List;

/**
 * 从某个风险源头到目标地址的一条可回溯传播路径（一个风险贡献来源）。
 *
 * @param sourceAddress 风险源头地址
 * @param baseScore     源头的初始风险分
 * @param hops          传播经过的跳（空列表表示目标地址本身即源头）
 * @param contribution  该路径对目标地址的风险贡献分（衰减后）
 */
public record RiskPath(String sourceAddress,
                       double baseScore,
                       List<PropagationHop> hops,
                       double contribution) {

    /** 路径终点地址：无跳时为源头本身，否则为最后一跳的收款方。 */
    public String targetAddress() {
        if (hops.isEmpty()) {
            return sourceAddress;
        }
        return hops.get(hops.size() - 1).toAddress();
    }

    /** 传播跳数（0 表示源头自身）。 */
    public int hopCount() {
        return hops.size();
    }
}
