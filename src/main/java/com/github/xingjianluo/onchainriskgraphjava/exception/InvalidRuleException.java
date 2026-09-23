package com.github.xingjianluo.onchainriskgraphjava.exception;

/**
 * 非法或相互冲突的风险规则：不得静默生效，直接拒绝变更。
 */
public class InvalidRuleException extends ApiException {

    public InvalidRuleException(String message) {
        super("INVALID_RULE", message);
    }

    @Override
    public int httpStatus() {
        return 422;
    }
}
