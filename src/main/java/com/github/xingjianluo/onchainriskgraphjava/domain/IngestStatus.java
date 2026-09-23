package com.github.xingjianluo.onchainriskgraphjava.domain;

/**
 * 交易入账结果状态：
 * ACCEPTED  —— 首次写入，资金流与风险指标计入一次；
 * DUPLICATE —— 同 txId 且内容完全一致的重复投递，幂等忽略，不再计入。
 * 内容冲突（同 txId 不同内容）不走该枚举，而是抛出 ConflictingTransactionException。
 */
public enum IngestStatus {
    ACCEPTED,
    DUPLICATE
}
