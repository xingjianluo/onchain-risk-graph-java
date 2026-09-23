package com.github.xingjianluo.onchainriskgraphjava.model;

/**
 * 链上交易流水的内存表示。
 *
 * @param txId        交易唯一标识（幂等键）
 * @param fromAddress 出资地址
 * @param toAddress   收款地址
 * @param amount      金额，必须为正
 * @param visibleAt   该交易对查询可见的时间点（毫秒），快照判定依据
 */
public record Transaction(String txId, String fromAddress, String toAddress,
                          long amount, long visibleAt) {
}
