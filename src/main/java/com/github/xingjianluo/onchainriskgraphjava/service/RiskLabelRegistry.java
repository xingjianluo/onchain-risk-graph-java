package com.github.xingjianluo.onchainriskgraphjava.service;

import com.github.xingjianluo.onchainriskgraphjava.model.RiskLabel;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitResult;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 风险地址标记注册表（内存、线程安全）。
 * 幂等键为地址；同一地址重复登记且内容一致视为重复，内容不一致拒绝。
 */
@Component
public class RiskLabelRegistry {

    private static final Logger log = LoggerFactory.getLogger(RiskLabelRegistry.class);

    private final ConcurrentMap<String, RiskLabel> byAddress = new ConcurrentHashMap<>();

    public SubmitResult<RiskLabel> register(RiskLabel label) {
        String invalid = validate(label);
        if (invalid != null) {
            log.warn("风险标记非法被拒绝: label={}, 原因={}", label, invalid);
            return new SubmitResult<>(SubmitStatus.REJECTED_INVALID, invalid, null);
        }

        RiskLabel existing = byAddress.putIfAbsent(label.address(), label);
        if (existing == null) {
            log.info("风险标记登记成功 address={}, baseScore={}, effectiveAt={}",
                    label.address(), label.baseScore(), label.effectiveAt());
            return new SubmitResult<>(SubmitStatus.ACCEPTED, "风险标记首次登记", label);
        }
        if (existing.equals(label)) {
            log.info("风险标记重复投递(内容一致)幂等忽略 address={}", label.address());
            return new SubmitResult<>(SubmitStatus.DUPLICATE,
                    "普通重复投递：地址 " + label.address() + " 已被标记且内容一致，已幂等忽略", existing);
        }

        String diff = describeDiff(existing, label);
        log.warn("风险标记冲突被拒绝 address={}, 差异={}", label.address(), diff);
        return new SubmitResult<>(SubmitStatus.REJECTED_CONFLICT,
                "内容冲突：地址 " + label.address() + " 的风险标记已存在但内容不一致 ["
                        + diff + "]，拒绝写入", existing);
    }

    public List<RiskLabel> effectiveLabels(long asOf) {
        return filterEffective(snapshotLabels(), asOf);
    }

    /**
     * 采集时刻已登记风险标记的全量不可变副本。
     * 批量回放开始时采集一次；采集之后并发登记的风险地址不会进入该副本，
     * 哪怕其 effectiveAt 自称在过去。
     */
    public List<RiskLabel> snapshotLabels() {
        return List.copyOf(byAddress.values());
    }

    /** 在给定快照副本上按 effectiveAt &lt;= asOf 过滤并按地址确定性排序 */
    public static List<RiskLabel> filterEffective(List<RiskLabel> snapshot, long asOf) {
        return snapshot.stream()
                .filter(l -> l.effectiveAt() <= asOf)
                .sorted(Comparator.comparing(RiskLabel::address))
                .toList();
    }

    private static String validate(RiskLabel label) {
        if (label == null) {
            return "风险标记不能为空";
        }
        if (label.address() == null || label.address().isBlank()) {
            return "address 不能为空";
        }
        if (Double.isNaN(label.baseScore()) || label.baseScore() <= 0 || label.baseScore() > 1) {
            return "baseScore 必须在 (0,1] 内，实际: " + label.baseScore();
        }
        if (label.effectiveAt() < 0) {
            return "effectiveAt 不能为负数，实际: " + label.effectiveAt();
        }
        return null;
    }

    private static String describeDiff(RiskLabel oldL, RiskLabel newL) {
        StringBuilder sb = new StringBuilder();
        if (oldL.baseScore() != newL.baseScore()) {
            sb.append("baseScore: ").append(oldL.baseScore())
              .append(" != ").append(newL.baseScore()).append("; ");
        }
        if (oldL.effectiveAt() != newL.effectiveAt()) {
            sb.append("effectiveAt: ").append(oldL.effectiveAt())
              .append(" != ").append(newL.effectiveAt()).append("; ");
        }
        return sb.toString().strip();
    }
}
