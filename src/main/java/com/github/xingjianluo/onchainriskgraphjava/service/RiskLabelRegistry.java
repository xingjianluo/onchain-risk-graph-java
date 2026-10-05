package com.github.xingjianluo.onchainriskgraphjava.service;

import com.github.xingjianluo.onchainriskgraphjava.ledger.CommitClock;
import com.github.xingjianluo.onchainriskgraphjava.ledger.Committed;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskLabel;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitResult;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final ConcurrentMap<String, Committed<RiskLabel>> byAddress = new ConcurrentHashMap<>();
    private final CommitClock clock;

    /** 兼容旧调用方：自带独立时钟（不与其他存储共享水位） */
    public RiskLabelRegistry() {
        this(new CommitClock());
    }

    @Autowired
    public RiskLabelRegistry(CommitClock clock) {
        this.clock = clock;
    }

    public SubmitResult<RiskLabel> register(RiskLabel label) {
        String invalid = validate(label);
        if (invalid != null) {
            log.warn("风险标记非法被拒绝: label={}, 原因={}", label, invalid);
            return new SubmitResult<>(SubmitStatus.REJECTED_INVALID, invalid, null);
        }

        // 分配提交序号与落库必须在同一临界区，保证批量回放的水位不会看到“半提交”状态
        Committed<RiskLabel> existing;
        synchronized (clock) {
            existing = byAddress.putIfAbsent(label.address(),
                    new Committed<>(clock.next(), label));
        }
        if (existing == null) {
            log.info("风险标记登记成功 address={}, baseScore={}, effectiveAt={}",
                    label.address(), label.baseScore(), label.effectiveAt());
            return new SubmitResult<>(SubmitStatus.ACCEPTED, "风险标记首次登记", label);
        }
        if (existing.value().equals(label)) {
            log.info("风险标记重复投递(内容一致)幂等忽略 address={}", label.address());
            return new SubmitResult<>(SubmitStatus.DUPLICATE,
                    "普通重复投递：地址 " + label.address() + " 已被标记且内容一致，已幂等忽略",
                    existing.value());
        }

        String diff = describeDiff(existing.value(), label);
        log.warn("风险标记冲突被拒绝 address={}, 差异={}", label.address(), diff);
        return new SubmitResult<>(SubmitStatus.REJECTED_CONFLICT,
                "内容冲突：地址 " + label.address() + " 的风险标记已存在但内容不一致 ["
                        + diff + "]，拒绝写入", existing.value());
    }

    /** 截至 asOf（含）已生效的全部风险标记（不限提交水位） */
    public List<RiskLabel> effectiveLabels(long asOf) {
        return effectiveLabels(asOf, Long.MAX_VALUE);
    }

    /**
     * 截至 asOf（含）已生效、且提交序号不超过 {@code maxSeq} 的风险标记。
     * 批量历史回放用批次开始时固定的水位调用本方法：回放期间新登记的标记
     * （哪怕 effectiveAt 落在回放区间内）对整批不可见。
     */
    public List<RiskLabel> effectiveLabels(long asOf, long maxSeq) {
        return byAddress.values().stream()
                .filter(c -> c.seq() <= maxSeq)
                .map(Committed::value)
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
