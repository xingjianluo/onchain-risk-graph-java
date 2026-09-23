package com.github.xingjianluo.onchainriskgraphjava.model;

import java.util.List;

/**
 * 一条可回溯的风险传播路径结论。
 *
 * @param sourceAddress   风险源地址
 * @param sourceBaseScore 风险源基础分
 * @param hops            传播跳数
 * @param contribution    该路径对目标地址的贡献分 = baseScore * decay^hops
 * @param route           传播路径上的地址序列（含风险源与目标）
 * @param txIds           路径各跳对应的交易标识序列
 */
public record RiskPath(String sourceAddress, double sourceBaseScore, int hops,
                       double contribution, List<String> route, List<String> txIds) {
}
