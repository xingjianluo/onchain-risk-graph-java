package com.github.xingjianluo.onchainriskgraphjava.exception;

/**
 * 同一交易标识（txId）但交易内容不一致：必须拒绝。
 * 与普通重复投递（内容一致 -> 幂等成功）通过 409 + CONFLICT 明确区分。
 */
public class ConflictingTransactionException extends ApiException {

    public ConflictingTransactionException(String txId, String existing, String incoming) {
        super("CONFLICT", "交易标识冲突: txId=" + txId + "，已存在内容[" + existing
                + "]与新提交内容[" + incoming + "]不一致，已拒绝");
    }

    @Override
    public int httpStatus() {
        return 409;
    }
}
