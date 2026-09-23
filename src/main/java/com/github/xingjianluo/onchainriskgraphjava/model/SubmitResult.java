package com.github.xingjianluo.onchainriskgraphjava.model;

/**
 * @param status  结果状态
 * @param message 可解释的原因说明
 * @param payload 首次写入时为生效对象，其余情况为已存在对象或 null
 */
public record SubmitResult<T>(SubmitStatus status, String message, T payload) {

    public boolean accepted() {
        return status == SubmitStatus.ACCEPTED;
    }
}
