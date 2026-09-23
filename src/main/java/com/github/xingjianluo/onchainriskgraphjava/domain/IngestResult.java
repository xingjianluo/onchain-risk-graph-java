package com.github.xingjianluo.onchainriskgraphjava.domain;

/**
 * 入账请求的处理结果。
 *
 * @param txId    交易标识
 * @param status  ACCEPTED / DUPLICATE
 * @param applied 是否真正新计入账本（重复投递为 false）
 * @param message 可解释的处理说明
 */
public record IngestResult(String txId,
                           IngestStatus status,
                           boolean applied,
                           String message) {
}
