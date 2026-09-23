package com.github.xingjianluo.onchainriskgraphjava.rule;

import com.github.xingjianluo.onchainriskgraphjava.exception.InvalidInputException;
import com.github.xingjianluo.onchainriskgraphjava.exception.InvalidRuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 规则注册表。
 *
 * 一致性语义：
 * 1. 规则以版本号为唯一键，注册后不可修改：同版本号重复提交且内容完全一致，
 *    幂等返回已有规则；同版本号内容不同，属于冲突规则，抛
 *    {@link InvalidRuleException}，绝不静默覆盖生效。
 * 2. 注册即校验取值范围，非法规则不会进入注册表。
 * 3. 每次风险结论都携带生成时所用 ruleVersion；按时间点查询时自动选择
 *    effectiveFrom &lt;= asOf 中生效最晚的一版，从而可复现历史结论。
 * 4. 内置 default-v1 规则自时间起点生效，保证任何时点都有确定可用的规则。
 */
@Component
public class RuleRegistry {

    private static final Logger log = LoggerFactory.getLogger(RuleRegistry.class);

    /** 内置默认规则：3 跳、每跳 0.5 衰减、阈值 0.5。 */
    public static final RiskRule DEFAULT_RULE =
            new RiskRule("default-v1", 3, 0.5d, 0.5d, 0L, "内置默认规则");

    private final ConcurrentMap<String, RiskRule> byVersion = new ConcurrentHashMap<>();

    public RuleRegistry() {
        // 直接放入，绕过面向外部输入的校验（内置规则本身合法）
        byVersion.put(DEFAULT_RULE.version(), DEFAULT_RULE);
    }

    /**
     * 注册一个新版本规则。
     *
     * @throws InvalidRuleException 规则非法，或版本号与既有规则冲突
     */
    public RiskRule register(RiskRule rule) {
        validate(rule);

        RiskRule existing = byVersion.putIfAbsent(rule.version(), rule);
        if (existing != null) {
            if (existing.equals(rule)) {
                log.info("[规则管理] 规则重复提交且内容一致，幂等忽略 version={}", rule.version());
                return existing;
            }
            log.warn("[规则管理] 规则版本冲突拒绝 version={} 已存=[{}] 新提交=[{}]",
                    rule.version(), describe(existing), describe(rule));
            throw new InvalidRuleException("规则版本冲突: version=" + rule.version()
                    + " 已存在不同内容的规则[" + describe(existing)
                    + "]，新提交[" + describe(rule) + "]，拒绝静默覆盖");
        }
        log.info("[规则管理] 新版本规则生效 version={} maxHops={} decay={} threshold={} effectiveFrom={}",
                rule.version(), rule.maxHops(), rule.decay(), rule.threshold(), rule.effectiveFrom());
        return rule;
    }

    /**
     * 选择某时点生效的规则：effectiveFrom &lt;= asOf 中生效时间最晚者；
     * 同一 effectiveFrom 并列时取版本号最小者，保证确定性。
     */
    public RiskRule ruleAt(long asOf) {
        return byVersion.values().stream()
                .filter(r -> r.effectiveFrom() <= asOf)
                // 生效时间最晚；同一生效时间并列时取版本号最小者，保证确定性
                .sorted(Comparator.comparingLong(RiskRule::effectiveFrom).reversed()
                        .thenComparing(RiskRule::version))
                .findFirst()
                .orElse(DEFAULT_RULE);
    }

    /** 按版本号精确获取规则。 */
    public RiskRule ruleByVersion(String version) {
        RiskRule rule = byVersion.get(version);
        if (rule == null) {
            throw new InvalidInputException("规则版本不存在: " + version);
        }
        return rule;
    }

    /** 全部规则版本，按生效时间升序。 */
    public List<RiskRule> versions() {
        return byVersion.values().stream()
                .sorted(Comparator.comparingLong(RiskRule::effectiveFrom)
                        .thenComparing(RiskRule::version))
                .toList();
    }

    private void validate(RiskRule rule) {
        if (rule == null) {
            throw new InvalidRuleException("规则不能为空");
        }
        if (rule.version() == null || rule.version().isBlank()) {
            throw new InvalidRuleException("规则版本号 version 不能为空");
        }
        if (rule.maxHops() < 0 || rule.maxHops() > 20) {
            throw new InvalidRuleException(
                    "maxHops 必须在 [0,20] 区间, version=" + rule.version());
        }
        if (Double.isNaN(rule.decay()) || rule.decay() <= 0 || rule.decay() > 1) {
            throw new InvalidRuleException(
                    "decay 必须在 (0,1] 区间, version=" + rule.version());
        }
        if (Double.isNaN(rule.threshold()) || rule.threshold() < 0 || rule.threshold() > 1) {
            throw new InvalidRuleException(
                    "threshold 必须在 [0,1] 区间, version=" + rule.version());
        }
        if (rule.effectiveFrom() < 0) {
            throw new InvalidRuleException(
                    "effectiveFrom 不能为负, version=" + rule.version());
        }
    }

    private static String describe(RiskRule r) {
        return "{maxHops=" + r.maxHops() + ", decay=" + r.decay()
                + ", threshold=" + r.threshold() + ", effectiveFrom=" + r.effectiveFrom() + "}";
    }
}
