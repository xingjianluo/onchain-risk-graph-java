package com.github.xingjianluo.onchainriskgraphjava.query;

import com.github.xingjianluo.onchainriskgraphjava.model.QueryRejection;
import com.github.xingjianluo.onchainriskgraphjava.model.RejectReason;

import java.util.NoSuchElementException;

/**
 * 查询类资源未找到（如时间点前无生效规则、指定版本不存在）。
 * 继承 {@link NoSuchElementException} 以保持既有调用方语义不回归。
 */
public class QueryNotFoundException extends NoSuchElementException implements RejectedQuery {

    private final RejectReason reason;

    public QueryNotFoundException(RejectReason reason, String message) {
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
