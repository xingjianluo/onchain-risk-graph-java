package com.github.xingjianluo.onchainriskgraphjava.service;

/**
 * 批量回放的一致性视图边界：在回放开始时刻截取，
 * 之后新入账的交易、新登记的标记、新发布的规则版本都不会进入本次回放。
 *
 * @param txSeq          账本入账顺序号上限
 * @param labelSeq       风险标记登记顺序号上限
 * @param maxRuleVersion 规则版本号上限
 */
public record ReplayScope(long txSeq, long labelSeq, int maxRuleVersion) {

    /** 不限制边界的视图（等价于普通单点查询的语义：看到当前一切可见数据） */
    public static ReplayScope unbounded() {
        return new ReplayScope(Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE);
    }
}
