package com.github.xingjianluo.onchainriskgraphjava.service;

import com.github.xingjianluo.onchainriskgraphjava.model.RuleVersion;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitResult;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 风险规则版本管理（内存、线程安全）。
 *
 * <ul>
 *   <li>每个版本一经发布即不可变；</li>
 *   <li>版本号从 1 递增，effectiveAt 必须严格递增（否则 REJECTED_CONFLICT，不静默覆盖）；</li>
 *   <li>字段取值非法：REJECTED_INVALID；</li>
 *   <li>历史结论可通过当时有效的版本复现。</li>
 * </ul>
 */
@Component
public class RuleService {

    private static final Logger log = LoggerFactory.getLogger(RuleService.class);

    private final List<RuleVersion> versions = new ArrayList<>();

    public synchronized SubmitResult<RuleVersion> publish(int maxHops, double decay,
                                                          double riskThreshold, long effectiveAt) {
        String invalid = validateParams(maxHops, decay, riskThreshold, effectiveAt);
        if (invalid != null) {
            log.warn("规则非法被拒绝: {}", invalid);
            return new SubmitResult<>(SubmitStatus.REJECTED_INVALID, invalid, null);
        }
        if (!versions.isEmpty()) {
            RuleVersion last = versions.get(versions.size() - 1);
            if (effectiveAt <= last.effectiveAt()) {
                String msg = "规则时间线冲突：新版本 effectiveAt=" + effectiveAt
                        + " 必须严格晚于当前最新版本(v" + last.version()
                        + ")的 effectiveAt=" + last.effectiveAt();
                log.warn(msg);
                return new SubmitResult<>(SubmitStatus.REJECTED_CONFLICT, msg, last);
            }
        }
        RuleVersion rule = new RuleVersion(versions.size() + 1, effectiveAt,
                maxHops, decay, riskThreshold);
        versions.add(rule);
        log.info("规则版本发布 v{}: effectiveAt={}, maxHops={}, decay={}, threshold={}",
                rule.version(), rule.effectiveAt(), rule.maxHops(),
                rule.decay(), rule.riskThreshold());
        return new SubmitResult<>(SubmitStatus.ACCEPTED, "规则版本 v" + rule.version() + " 已发布", rule);
    }

    /** 某时间点适用的规则版本（effectiveAt <= asOf 的最新版本） */
    public synchronized Optional<RuleVersion> ruleAt(long asOf) {
        return ruleAt(asOf, Integer.MAX_VALUE);
    }

    /**
     * 某时间点适用的规则版本，但只考虑版本号不超过 maxVersion 的已发布版本。
     * 批量回放用 maxVersion 锁住开始时刻的规则线，回放期间新发布的版本不会被算进来。
     */
    public synchronized Optional<RuleVersion> ruleAt(long asOf, int maxVersion) {
        return versions.stream()
                .filter(r -> r.version() <= maxVersion)
                .filter(r -> r.effectiveAt() <= asOf)
                .max(Comparator.comparingInt(RuleVersion::version));
    }

    /** 按版本号取某个已发布的不可变版本（用于显式指定版本复现/对比） */
    public synchronized Optional<RuleVersion> version(int version) {
        if (version < 1 || version > versions.size()) {
            return Optional.empty();
        }
        return Optional.of(versions.get(version - 1));
    }

    public synchronized int currentVersionNumber() {
        return versions.size();
    }

    static String validateParams(int maxHops, double decay, double riskThreshold, long effectiveAt) {
        if (maxHops < 0) {
            return "maxHops 必须 >= 0，实际: " + maxHops;
        }
        if (Double.isNaN(decay) || decay <= 0 || decay > 1) {
            return "decay 必须在 (0,1] 内，实际: " + decay;
        }
        if (Double.isNaN(riskThreshold) || riskThreshold <= 0 || riskThreshold > 1) {
            return "riskThreshold 必须在 (0,1] 内，实际: " + riskThreshold;
        }
        if (effectiveAt < 0) {
            return "effectiveAt 不能为负数，实际: " + effectiveAt;
        }
        return null;
    }
}
