package com.github.xingjianluo.onchainriskgraphjava.model;

/**
 * 提交类操作（交易入账 / 风险标记 / 规则发布）的统一结果状态。
 */
public enum SubmitStatus {
    /** 首次写入成功 */
    ACCEPTED,
    /** 普通重复投递：标识相同且内容一致，幂等忽略 */
    DUPLICATE,
    /** 冲突：标识相同但内容不一致，拒绝 */
    REJECTED_CONFLICT,
    /** 非法：字段校验不通过，拒绝 */
    REJECTED_INVALID
}
