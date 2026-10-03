package com.github.xingjianluo.onchainriskgraphjava.query;

import com.github.xingjianluo.onchainriskgraphjava.model.QueryRejection;
import com.github.xingjianluo.onchainriskgraphjava.model.RejectReason;

/**
 * 查询参数非法。继承 {@link IllegalArgumentException} 以保持既有调用方语义不回归。
 */
public class QueryInvalidException extends IllegalArgumentException implements RejectedQuery {

    private final RejectReason reason;

    public QueryInvalidException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    @Override
    public RejectReason reason() {
        return reason;
    }

    @Override
    public QueryRejection rejection() {
        return new QueryRejection(reason.code(), reason.name(), getMessage());
    }
}
