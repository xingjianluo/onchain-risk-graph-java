package com.github.xingjianluo.onchainriskgraphjava.service;

import com.github.xingjianluo.onchainriskgraphjava.model.QueryOutcome;
import com.github.xingjianluo.onchainriskgraphjava.model.QueryRejectReason;
import com.github.xingjianluo.onchainriskgraphjava.model.QueryRejection;
import com.github.xingjianluo.onchainriskgraphjava.model.ReplayItem;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskConclusion;
import com.github.xingjianluo.onchainriskgraphjava.model.RuleDiffResult;
import com.github.xingjianluo.onchainriskgraphjava.model.RuleVersion;
import com.github.xingjianluo.onchainriskgraphjava.model.ScoreChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 历史回放与规则版本对比。
 *
 * <ul>
 *   <li>批量回放：开始时刻截取一致的 {@link ReplayScope}（账本/标记/规则三线定格），
 *       回放期间的新入账、新标记、新规则都不会进入本批结果；
 *       每个时间点的结论与单独查询完全一致，返回顺序与请求顺序一致；</li>
 *   <li>版本对比：同一时间点用两个已发布版本分别全量评估，
 *       列出正常→高风险、高风险→正常的地址及各自分值变化。</li>
 * </ul>
 */
@Component
public class RiskReplayService {

    private static final Logger log = LoggerFactory.getLogger(RiskReplayService.class);

    private final RiskPropagationService propagationService;
    private final RuleService ruleService;

    public RiskReplayService(RiskPropagationService propagationService, RuleService ruleService) {
        this.propagationService = propagationService;
        this.ruleService = ruleService;
    }

    /**
     * 批量历史回放：对一组时间点逐个评估同一地址。
     * 整批共用开始时刻截取的回放边界；单个时间点的拒绝不影响其余时间点。
     *
     * @param ruleVersion 显式指定的规则版本（可空，空则按各时间点语义选取）
     * @return 与 asOfList 一一对应、顺序一致的结果列表
     */
    public List<ReplayItem> replay(String address, List<Long> asOfList, Integer ruleVersion) {
        ReplayScope scope = propagationService.captureScope();
        log.info("批量回放开始 address={}, 时间点数={}, 指定规则版本={}, 回放边界={}",
                address, asOfList == null ? 0 : asOfList.size(), ruleVersion, scope);
        List<ReplayItem> items = new ArrayList<>();
        if (asOfList == null || asOfList.isEmpty()) {
            items.add(new ReplayItem(-1L, QueryOutcome.rejected(new QueryRejection(
                    QueryRejectReason.EMPTY_REPLAY_REQUEST, "批量回放的时间点列表不能为空"))));
            return items;
        }
        for (Long asOf : asOfList) {
            long t = asOf == null ? -1L : asOf;
            QueryOutcome<RiskConclusion> outcome = propagationService.evaluateOutcome(address, t, ruleVersion, scope);
            items.add(new ReplayItem(t, outcome));
            log.info("  回放点 t={}: {}", t, outcome.isOk()
                    ? "score=" + outcome.payload().riskScore()
                      + ", risky=" + outcome.payload().risky()
                      + ", ruleVersion=v" + outcome.payload().ruleVersion()
                    : "拒绝 reason=" + outcome.rejection().reason()
                      + ", message=" + outcome.rejection().message());
        }
        log.info("批量回放完成 address={}, 成功={}, 拒绝={}",
                address,
                items.stream().filter(i -> i.outcome().isOk()).count(),
                items.stream().filter(i -> !i.outcome().isOk()).count());
        return List.copyOf(items);
    }

    /**
     * 规则版本对比：同一时间点 asOf，分别用 baseVersion 与 targetVersion 全量评估，
     * 输出判定翻转与分值变化。两个版本都必须是已发布版本，否则返回可解释拒绝。
     */
    public QueryOutcome<RuleDiffResult> compare(long asOf, int baseVersion, int targetVersion) {
        if (asOf < 0) {
            return QueryOutcome.rejected(new QueryRejection(
                    QueryRejectReason.INVALID_AS_OF, "asOf 不能为负数，实际: " + asOf));
        }
        Optional<RuleVersion> base = ruleService.version(baseVersion);
        if (baseVersion <= 0 || base.isEmpty()) {
            return QueryOutcome.rejected(new QueryRejection(
                    baseVersion <= 0 ? QueryRejectReason.INVALID_RULE_VERSION
                                     : QueryRejectReason.UNKNOWN_RULE_VERSION,
                    "基准规则版本 v" + baseVersion + " 不可用（当前已发布到 v"
                            + ruleService.currentVersionNumber() + "）"));
        }
        Optional<RuleVersion> target = ruleService.version(targetVersion);
        if (targetVersion <= 0 || target.isEmpty()) {
            return QueryOutcome.rejected(new QueryRejection(
                    targetVersion <= 0 ? QueryRejectReason.INVALID_RULE_VERSION
                                       : QueryRejectReason.UNKNOWN_RULE_VERSION,
                    "目标规则版本 v" + targetVersion + " 不可用（当前已发布到 v"
                            + ruleService.currentVersionNumber() + "）"));
        }

        ReplayScope scope = propagationService.captureScope();
        List<RiskConclusion> baseAll = propagationService.evaluateAll(asOf, base.get(), scope);
        List<RiskConclusion> targetAll = propagationService.evaluateAll(asOf, target.get(), scope);

        Map<String, RiskConclusion> baseByAddr = new TreeMap<>();
        baseAll.forEach(c -> baseByAddr.put(c.address(), c));
        Map<String, RiskConclusion> targetByAddr = new TreeMap<>();
        targetAll.forEach(c -> targetByAddr.put(c.address(), c));

        List<ScoreChange> becameRisky = new ArrayList<>();
        List<ScoreChange> cleared = new ArrayList<>();
        List<ScoreChange> changed = new ArrayList<>();
        var addresses = new java.util.TreeSet<String>();
        addresses.addAll(baseByAddr.keySet());
        addresses.addAll(targetByAddr.keySet());
        for (String address : addresses) {
            RiskConclusion b = baseByAddr.get(address);
            RiskConclusion t = targetByAddr.get(address);
            double bs = b == null ? 0.0 : b.riskScore();
            double ts = t == null ? 0.0 : t.riskScore();
            boolean br = b != null && b.risky();
            boolean tr = t != null && t.risky();
            if (bs == ts && br == tr) {
                continue;
            }
            ScoreChange change = new ScoreChange(address, bs, ts, ts - bs, br, tr);
            changed.add(change);
            if (!br && tr) {
                becameRisky.add(change);
            } else if (br && !tr) {
                cleared.add(change);
            }
        }
        RuleDiffResult diff = new RuleDiffResult(asOf, baseVersion, targetVersion,
                List.copyOf(becameRisky), List.copyOf(cleared), List.copyOf(changed));

        log.info("规则版本对比 t={}, v{} -> v{}: 新增高风险={}, 解除高风险={}, 分值变化={}",
                asOf, baseVersion, targetVersion,
                becameRisky.size(), cleared.size(), changed.size());
        becameRisky.forEach(c -> log.info("  [正常->高风险] {} score {} -> {} (Δ{})",
                c.address(), fmt(c.baseScore()), fmt(c.targetScore()), fmt(c.delta())));
        cleared.forEach(c -> log.info("  [高风险->正常] {} score {} -> {} (Δ{})",
                c.address(), fmt(c.baseScore()), fmt(c.targetScore()), fmt(c.delta())));
        changed.stream()
                .filter(c -> c.baseRisky() == c.targetRisky())
                .forEach(c -> log.info("  [判定不变分值变化] {} score {} -> {} (Δ{})",
                        c.address(), fmt(c.baseScore()), fmt(c.targetScore()), fmt(c.delta())));
        return QueryOutcome.ok(diff);
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }
}
