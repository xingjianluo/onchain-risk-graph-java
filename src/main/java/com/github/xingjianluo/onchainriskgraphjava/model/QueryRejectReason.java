package com.github.xingjianluo.onchainriskgraphjava.model;

/**
 * 查询类操作（风险评估 / 回放 / 对比）被拒绝的稳定原因码。
 * 每种原因彼此可区分，随响应原样返回，供调用方机读。
 */
public enum QueryRejectReason {
    /** 地址为空或全空白 */
    INVALID_ADDRESS,
    /** 时间点非法（如负数） */
    INVALID_AS_OF,
    /** 显式指定的规则版本号非法（如 <= 0） */
    INVALID_RULE_VERSION,
    /** 显式指定的规则版本不存在（从未发布过） */
    UNKNOWN_RULE_VERSION,
    /** 该时间点之前没有任何已生效的规则版本，无法判定 */
    NO_EFFECTIVE_RULE,
    /** 批量回放请求为空或时间点列表为空 */
    EMPTY_REPLAY_REQUEST
}
