package com.github.xingjianluo.onchainriskgraphjava.model;

/**
 * 查询类请求被拒绝的稳定原因码。
 * 每个原因码对应固定的 HTTP 状态码与可机读 code，彼此可区分。
 */
public enum RejectReason {
    /** 地址为 null 或空白 */
    BLANK_ADDRESS(400),
    /** asOf 取值非法（如负数） */
    INVALID_AS_OF(400),
    /** 缺少必填的 asOf 参数 */
    MISSING_AS_OF(400),
    /** 其它参数类型/取值非法 */
    INVALID_PARAMETER(400),
    /** 批量请求体非法：空批次、点位数超限、点位字段缺失等 */
    INVALID_BATCH(400),
    /** 显式指定的规则版本不存在 */
    UNKNOWN_RULE_VERSION(404),
    /** 该时间点之前没有任何已生效的规则版本 */
    NO_EFFECTIVE_RULE(404);

    private final int httpStatus;

    RejectReason(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return name();
    }
}
