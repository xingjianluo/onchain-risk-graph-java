package com.github.xingjianluo.onchainriskgraphjava.ledger;

/**
 * 一条带提交序号的已落库数据。
 *
 * @param seq   全局提交序号（由 {@link CommitClock} 分配，单调递增）
 * @param value 业务数据本体（幂等比较、相等性判断只针对 value）
 */
public record Committed<T>(long seq, T value) {
}
