package com.github.xingjianluo.onchainriskgraphjava.service;

import com.github.xingjianluo.onchainriskgraphjava.model.RiskConclusion;
import com.github.xingjianluo.onchainriskgraphjava.model.RuleVerdictChange;
import com.github.xingjianluo.onchainriskgraphjava.model.RuleVersion;
import com.github.xingjianluo.onchainriskgraphjava.model.RuleVersionDiff;
import com.github.xingjianluo.onchainriskgraphjava.query.QueryInvalidException;
import com.github.xingjianluo.onchainriskgraphjava.query.QueryValidator;
import com.github.xingjianluo.onchainriskgraphjava.model.RejectReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则版本回放与版本间影响对比。
 *
 * <p>语义：
 * <ul>
 *   <li>数据口径始终是 asOf（含）的快照——只看该时点可见的交易与已生效标记；</li>
 *   <li>规则严格按显式指定的已发布版本计算，不按时间点自动选择，
 *       因此历史时点可被任意已发布版本复现，且绝不被之后发布的版本带偏；</li>
 *   <li>对比结果分三类：新升高风险 / 解除高风险 / 判定未翻但分值变化，
 *       每条差异携带两个版本的完整结论（含路径依据）。</li>
 * </ul>
 */
@Component
public class RuleReplayService {

    private static final Logger log = LoggerFactory.getLogger(RuleReplayService.class);

    private static final double SCORE_EPS = 1e-12;

    private final RuleService ruleService;
    private final RiskPropagationService propagationService;

    public RuleReplayService(RuleService ruleService,
                             RiskPropagationService propagationService) {
        this.ruleService = ruleService;
        this.propagationService = propagationService;
    }

    /** 用显式指定的已发布规则版本，在 asOf 快照上复现单地址结论 */
    public RiskConclusion evaluateWithVersion(String address, long asOf, int version) {
        return propagationService.evaluate(address, asOf, version);
    }

    /**
     * 同一时间点两个规则版本的判定差异对比。
     *
     * @param asOf     快照时间点
     * @param versionA 版本 A（基准），必须为已发布版本
     * @param versionB 版本 B（对照），必须为已发布版本
     * @param address  非空白时只对比该地址；空白时对比快照内全部地址
     */
    public RuleVersionDiff compareVersions(long asOf, Integer versionA, Integer versionB,
                                           String address) {
        long validAsOf = QueryValidator.requireAsOf(asOf);
        int a = requireVersionParam(versionA, "versionA");
        int b = requireVersionParam(versionB, "versionB");
        RuleVersion ruleA = ruleService.requireVersion(a);
        RuleVersion ruleB = ruleService.requireVersion(b);

        boolean singleAddress = address != null;
        // 显式给了 address（哪怕是空白）必须是合法地址；只有完全不传才表示全量对比
        String cleanAddress = singleAddress ? QueryValidator.requireAddress(address) : null;

        List<RiskConclusion> listA = singleAddress
                ? List.of(propagationService.evaluate(cleanAddress, validAsOf, a))
                : propagationService.evaluateAll(validAsOf, a);
        List<RiskConclusion> listB = singleAddress
                ? List.of(propagationService.evaluate(cleanAddress, validAsOf, b))
                : propagationService.evaluateAll(validAsOf, b);

        // 两边地址集合对齐（全量对比时两侧目标集合同源，单地址时只有一条）
        Map<String, RiskConclusion> byAddrB = new LinkedHashMap<>();
        listB.forEach(c -> byAddrB.put(c.address(), c));

        List<RuleVerdictChange> becameRisky = new ArrayList<>();
        List<RuleVerdictChange> becameSafe = new ArrayList<>();
        List<RuleVerdictChange> scoreOnlyChanged = new ArrayList<>();

        for (RiskConclusion ca : listA) {
            RiskConclusion cb = byAddrB.get(ca.address());
            if (cb == null) {
                continue;
            }
            double delta = cb.riskScore() - ca.riskScore();
            RuleVerdictChange change = new RuleVerdictChange(ca.address(),
                    ca.risky(), cb.risky(), ca.riskScore(), cb.riskScore(), delta, ca, cb);
            if (!ca.risky() && cb.risky()) {
                becameRisky.add(change);
            } else if (ca.risky() && !cb.risky()) {
                becameSafe.add(change);
            } else if (Math.abs(delta) > SCORE_EPS) {
                scoreOnlyChanged.add(change);
            }
        }
        Comparator<RuleVerdictChange> byAddress =
                Comparator.comparing(RuleVerdictChange::address);
        becameRisky.sort(byAddress);
        becameSafe.sort(byAddress);
        // 分值变化按变化幅度降序，并列按地址，差异最显著的在前
        scoreOnlyChanged.sort(Comparator
                .comparingDouble((RuleVerdictChange c) -> Math.abs(c.scoreDelta())).reversed()
                .thenComparing(RuleVerdictChange::address));

        RuleVersionDiff diff = new RuleVersionDiff(validAsOf, a, b,
                ruleA.riskThreshold(), ruleB.riskThreshold(),
                List.copyOf(becameRisky), List.copyOf(becameSafe),
                List.copyOf(scoreOnlyChanged), listA.size(), listA, listB);

        log.info("规则版本对比 asOf={}, v{} -> v{} (threshold {} -> {}): 新升高风险 {} 个 {}, "
                        + "解除高风险 {} 个 {}, 分值变化 {} 个{}",
                validAsOf, a, b, ruleA.riskThreshold(), ruleB.riskThreshold(),
                becameRisky.size(), describeAddresses(becameRisky),
                becameSafe.size(), describeAddresses(becameSafe),
                scoreOnlyChanged.size(), singleAddress ? "（单地址对比 " + cleanAddress + "）" : "");
        for (RuleVerdictChange c : becameRisky) {
            log.info("  [新升高风险] {}: score {} -> {} ({}, {}), 版本B路径数={}",
                    c.address(), fmt(c.scoreA()), fmt(c.scoreB()),
                    c.riskyA() ? "risky" : "safe", c.riskyB() ? "risky" : "safe",
                    c.conclusionB().contributions().size());
        }
        for (RuleVerdictChange c : becameSafe) {
            log.info("  [解除高风险] {}: score {} -> {} ({}, {}), 版本A路径数={}",
                    c.address(), fmt(c.scoreA()), fmt(c.scoreB()),
                    c.riskyA() ? "risky" : "safe", c.riskyB() ? "risky" : "safe",
                    c.conclusionA().contributions().size());
        }
        return diff;
    }

    private static int requireVersionParam(Integer version, String name) {
        if (version == null) {
            throw new QueryInvalidException(RejectReason.INVALID_PARAMETER,
                    "缺少必填的规则版本参数 " + name);
        }
        if (version <= 0) {
            throw new QueryInvalidException(RejectReason.UNKNOWN_RULE_VERSION,
                    "规则版本号必须为正整数，" + name + " 实际: " + version);
        }
        return version;
    }

    private static String describeAddresses(List<RuleVerdictChange> changes) {
        if (changes.isEmpty()) {
            return "";
        }
        List<String> addrs = changes.stream().map(RuleVerdictChange::address).toList();
        return " " + addrs;
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }
}
