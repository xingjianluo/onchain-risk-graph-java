package com.github.xingjianluo.onchainriskgraphjava.query;

import com.github.xingjianluo.onchainriskgraphjava.model.RejectReason;

/**
 * 查询参数统一校验：把空白地址、非法时间点等边界情况转成彼此可区分的拒绝异常。
 *
 * <p>原因码区分：
 * <ul>
 *   <li>{@link RejectReason#BLANK_ADDRESS}：地址为 null 或仅空白；</li>
 *   <li>{@link RejectReason#MISSING_AS_OF}：必填的 asOf 未提供；</li>
 *   <li>{@link RejectReason#INVALID_AS_OF}：asOf 取值非法（负数）；</li>
 *   <li>{@link RejectReason#UNKNOWN_RULE_VERSION}：显式指定的规则版本不存在或非法。</li>
 * </ul>
 */
public final class QueryValidator {

    private QueryValidator() {
    }

    /** 校验地址非空白，返回去除首尾空白后的地址 */
    public static String requireAddress(String address) {
        if (address == null || address.isBlank()) {
            throw new QueryInvalidException(RejectReason.BLANK_ADDRESS,
                    "地址不能为空或仅含空白字符");
        }
        return address.strip();
    }

    /** 校验 asOf 存在且非负（HTTP 层使用，区分“缺失”与“非法取值”） */
    public static long requireAsOf(Long asOf) {
        if (asOf == null) {
            throw new QueryInvalidException(RejectReason.MISSING_AS_OF,
                    "缺少必填参数 asOf（快照时间点，毫秒）");
        }
        return requireAsOf(asOf.longValue());
    }

    /** 校验 asOf 非负（服务层使用） */
    public static long requireAsOf(long asOf) {
        if (asOf < 0) {
            throw new QueryInvalidException(RejectReason.INVALID_AS_OF,
                    "asOf 不能为负数，实际: " + asOf);
        }
        return asOf;
    }

    /** 校验显式指定的规则版本号为正整数，返回原值 */
    public static int requireVersion(Integer version) {
        if (version == null) {
            throw new QueryInvalidException(RejectReason.INVALID_PARAMETER,
                    "规则版本号不能为空");
        }
        if (version <= 0) {
            throw new QueryInvalidException(RejectReason.UNKNOWN_RULE_VERSION,
                    "规则版本号必须为正整数，实际: " + version);
        }
        return version;
    }
}
