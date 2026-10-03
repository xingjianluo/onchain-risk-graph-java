package com.github.xingjianluo.onchainriskgraphjava.model;

/**
 * 一次查询被拒绝的可解释结果：稳定原因码 + 人类可读说明。
 *
 * @param reason  稳定原因码，彼此可区分
 * @param message 具体原因说明（含实际入参值，便于排查）
 */
public record QueryRejection(QueryRejectReason reason, String message) {
}
