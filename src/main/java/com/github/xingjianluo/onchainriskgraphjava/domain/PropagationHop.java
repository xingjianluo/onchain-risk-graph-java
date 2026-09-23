package com.github.xingjianluo.onchainriskgraphjava.domain;

/**
 * 风险传播路径上的一跳，对应一笔资金交易。
 *
 * @param txId        支撑该跳的交易标识
 * @param fromAddress 跳的起点地址
 * @param toAddress   跳的终点地址
 * @param timestamp   交易时间
 * @param hopIndex    从源头出发的第几跳（1 起）
 * @param edgeScore   经过该跳后路径上的累计衰减风险分
 */
public record PropagationHop(String txId,
                             String fromAddress,
                             String toAddress,
                             long timestamp,
                             int hopIndex,
                             double edgeScore) {
}
