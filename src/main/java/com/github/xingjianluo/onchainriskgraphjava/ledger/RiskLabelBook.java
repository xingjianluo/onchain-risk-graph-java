package com.github.xingjianluo.onchainriskgraphjava.ledger;

import com.github.xingjianluo.onchainriskgraphjava.domain.RiskLabel;
import com.github.xingjianluo.onchainriskgraphjava.exception.InvalidInputException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 风险标记册：保存风险传播的种子地址。
 * 只追加，按地址聚合；快照查询时只取 timestamp &lt;= asOf 的标记，
 * 同一地址取该时点最新一条（最新的人工结论为准）。
 */
@Component
public class RiskLabelBook {

    private static final Logger log = LoggerFactory.getLogger(RiskLabelBook.class);

    private final CopyOnWriteArrayList<RiskLabel> labels = new CopyOnWriteArrayList<>();

    public void add(RiskLabel label) {
        validate(label);
        labels.add(label);
        log.info("[风险标记] 新增风险源头 address={} baseScore={} reason={} @ {}",
                label.address(), label.baseScore(), label.reason(), label.timestamp());
    }

    /**
     * 某时点可见的每地址一条有效标记（取时间最新；并列时按 reason 确定性排序）。
     */
    public List<RiskLabel> visibleAt(long asOf) {
        return labels.stream()
                .filter(l -> l.timestamp() <= asOf)
                .sorted(Comparator.comparingLong(RiskLabel::timestamp)
                        .thenComparing(RiskLabel::reason,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(java.util.stream.Collectors.groupingBy(
                        RiskLabel::address,
                        java.util.LinkedHashMap::new,
                        // 已按时间升序，取每组最后一条
                        java.util.stream.Collectors.reducing(null, (a, b) -> b)))
                .values().stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(RiskLabel::address))
                .toList();
    }

    private void validate(RiskLabel label) {
        if (label == null) {
            throw new InvalidInputException("风险标记不能为空");
        }
        if (label.address() == null || label.address().isBlank()) {
            throw new InvalidInputException("风险标记 address 不能为空");
        }
        if (Double.isNaN(label.baseScore()) || label.baseScore() <= 0 || label.baseScore() > 1) {
            throw new InvalidInputException(
                    "baseScore 必须在 (0,1] 区间, address=" + label.address());
        }
        if (label.timestamp() <= 0) {
            throw new InvalidInputException(
                    "timestamp 必须为正数(epoch millis), address=" + label.address());
        }
    }
}
