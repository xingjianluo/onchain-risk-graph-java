package com.github.xingjianluo.onchainriskgraphjava.model;

import java.util.List;

/**
 * 某地址在某快照时间点的资金流汇总。
 */
public record FundFlow(String address, long asOf, long totalInflow, long totalOutflow,
                       List<String> inflowTxIds, List<String> outflowTxIds) {
}
