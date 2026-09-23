package com.github.xingjianluo.onchainriskgraphjava.domain;

import java.math.BigDecimal;

/**
 * 一笔链上资金转移（内存账本中的一条流水）。
 *
 * @param txId        交易唯一标识（幂等键）
 * @param fromAddress 出资地址
 * @param toAddress   收款地址
 * @param amount      转账金额（必须为正数）
 * @param timestamp   交易发生时间（epoch millis，事件时间）
 */
public record Transaction(String txId,
                          String fromAddress,
                          String toAddress,
                          BigDecimal amount,
                          long timestamp) {
}
