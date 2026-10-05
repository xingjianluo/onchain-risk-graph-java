package com.github.xingjianluo.onchainriskgraphjava.model;

import java.util.List;

/**
 * 批量回放开始时刻对全部数据源的一致性快照（不可变）。
 *
 * <p>一次批量回放自开始评估起只使用该快照：回放期间并发入账的交易、并发登记的风险地址、
 * 并发发布的规则版本（即便它们自称的 visibleAt/effectiveAt 落在回放区间内）一律不属于本快照，
 * 因而不会改变这一批任何历史点位的结论；而那些时间戳确实晚于某点位、回放开始前就已存在的数据，
 * 仍由各点位的 {@code visibleAt/effectiveAt <= asOf} 过滤正常排除，语义不变。
 *
 * @param capturedAt 快照采集时刻（墙钟毫秒，仅用于日志/诊断）
 * @param transactions 采集时刻账本中已入账的全部交易（幂等去重后）
 * @param labels       采集时刻已登记的全部风险地址标记
 * @param rules        采集时刻已发布的全部规则版本（按版本号升序，一经发布即不可变）
 */
public record RiskDataSnapshot(long capturedAt,
                               List<Transaction> transactions,
                               List<RiskLabel> labels,
                               List<RuleVersion> rules) {
}
