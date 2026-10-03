package com.github.xingjianluo.onchainriskgraphjava.model;

/**
 * 查询被拒绝时返回的可机读结果。
 *
 * @param reasonCode 稳定原因码（{@link RejectReason} 的名称）
 * @param reason     原因码（冗余字段，便于日志/展示直接取用）
 * @param message    人类可读的稳定原因说明
 */
public record QueryRejection(String reasonCode, String reason, String message) {
}
