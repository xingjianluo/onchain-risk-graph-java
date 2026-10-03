package com.github.xingjianluo.onchainriskgraphjava.model;

/**
 * 查询类操作的统一结果：要么携带结论载荷，要么携带可解释拒绝，二者恰居其一。
 *
 * @param <T> 结论载荷类型（如 RiskConclusion、RuleDiffResult）
 */
public record QueryOutcome<T>(T payload, QueryRejection rejection) {

    public static <T> QueryOutcome<T> ok(T payload) {
        return new QueryOutcome<>(payload, null);
    }

    public static <T> QueryOutcome<T> rejected(QueryRejection rejection) {
        return new QueryOutcome<>(null, rejection);
    }

    public boolean isOk() {
        return payload != null;
    }
}
