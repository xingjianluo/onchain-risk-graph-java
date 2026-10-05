package com.github.xingjianluo.onchainriskgraphjava.service;

import com.github.xingjianluo.onchainriskgraphjava.ledger.TransactionLedger;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskConclusion;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskLabel;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskPath;
import com.github.xingjianluo.onchainriskgraphjava.model.RuleVersion;
import com.github.xingjianluo.onchainriskgraphjava.model.Transaction;
import com.github.xingjianluo.onchainriskgraphjava.query.QueryValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 风险传播核心。
 *
 * <p>传播规则：
 * <ul>
 *   <li>方向沿资金流 from -> to；</li>
 *   <li>第 k 跳路径贡献 = 风险源 baseScore * decay^k；</li>
 *   <li>同一风险源到同一地址只保留“最大贡献路径”（decay≤1 时即最短跳数，
 *       并列时按 txId 字典序的确定性遍历顺序选取），环路与自环靠访问集剪枝，
 *       不会无限传播，也不会重复累加；</li>
 *   <li>多个风险源的贡献用 noisy-OR 聚合：score = 1 - Π(1-c_i)，保证分值不超过 1；</li>
 *   <li>score >= riskThreshold 判定为高风险。</li>
 * </ul>
 * 所有计算只基于给定快照时点之前（含）可见的交易与已生效标记/规则，纯读不改状态。
 */
@Component
public class RiskPropagationService {

    private static final Logger log = LoggerFactory.getLogger(RiskPropagationService.class);

    private final TransactionLedger ledger;
    private final RiskLabelRegistry labelRegistry;
    private final RuleService ruleService;

    public RiskPropagationService(TransactionLedger ledger,
                                  RiskLabelRegistry labelRegistry,
                                  RuleService ruleService) {
        this.ledger = ledger;
        this.labelRegistry = labelRegistry;
        this.ruleService = ruleService;
    }

    /** 构建仅含 asOf（含）之前可见交易的不可变图视图（不限提交水位） */
    public GraphView buildSnapshot(long asOf) {
        return buildSnapshot(asOf, Long.MAX_VALUE);
    }

    /**
     * 构建仅含 asOf（含）之前可见、且提交序号不超过 {@code maxSeq} 的交易的不可变图视图。
     * 批量历史回放用批次开始时固定的水位调用，回放期间新入账的交易对整批不可见。
     */
    public GraphView buildSnapshot(long asOf, long maxSeq) {
        List<Transaction> txs = ledger.visibleTransactions(asOf, maxSeq);
        Map<String, List<Edge>> outgoing = new HashMap<>();
        Set<String> nodes = new TreeSet<>();
        for (Transaction tx : txs) {
            outgoing.computeIfAbsent(tx.fromAddress(), k -> new ArrayList<>())
                    .add(new Edge(tx.txId(), tx.fromAddress(), tx.toAddress(), tx.amount()));
            nodes.add(tx.fromAddress());
            nodes.add(tx.toAddress());
        }
        outgoing.values().forEach(edges -> edges.sort(Comparator.comparing(Edge::txId)));
        // 不可变化，保证视图稳定
        Map<String, List<Edge>> frozen = new LinkedHashMap<>();
        outgoing.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> frozen.put(e.getKey(), List.copyOf(e.getValue())));
        return new GraphView(asOf, List.copyOf(txs), Collections.unmodifiableMap(frozen),
                List.copyOf(nodes));
    }

    /**
     * 在 asOf 时点的快照上评估某地址风险（默认语义）。
     * 规则版本自动选取 {@code effectiveAt <= asOf} 的最新版本；
     * 时间点前没有任何生效规则时抛出 {@link
     * com.github.xingjianluo.onchainriskgraphjava.query.QueryNotFoundException}
     * （原因码 {@code NO_EFFECTIVE_RULE}，仍是 {@link java.util.NoSuchElementException} 子类）。
     */
    public RiskConclusion evaluate(String address, long asOf) {
        String cleanAddress = QueryValidator.requireAddress(address);
        long validAsOf = QueryValidator.requireAsOf(asOf);
        RuleVersion rule = ruleService.requireRuleAt(validAsOf);
        return evaluateWithRule(cleanAddress, validAsOf, rule, false, Long.MAX_VALUE);
    }

    /**
     * 批量历史回放专用：在批次开始时固定的提交水位 {@code maxSeq} 上评估。
     * 数据（交易/标记）与规则版本都只取 {@code seq <= maxSeq} 的部分，
     * 回放期间新提交/新发布的内容对整批不可见；点位自己的 asOf 过滤照常生效。
     */
    public RiskConclusion evaluateFenced(String address, long asOf, long maxSeq) {
        String cleanAddress = QueryValidator.requireAddress(address);
        long validAsOf = QueryValidator.requireAsOf(asOf);
        RuleVersion rule = ruleService.requireRuleAt(validAsOf, maxSeq);
        return evaluateWithRule(cleanAddress, validAsOf, rule, false, maxSeq);
    }

    /**
     * 在 asOf 时点的快照上，<b>显式指定一个已经发布过的规则版本</b>复现结论。
     * 数据快照（交易/标记可见性）仍只取 asOf（含）之前；规则则严格使用指定版本，
     * 不随时间点自动选择，也不会被之后发布的新版本带偏。
     */
    public RiskConclusion evaluate(String address, long asOf, int version) {
        String cleanAddress = QueryValidator.requireAddress(address);
        long validAsOf = QueryValidator.requireAsOf(asOf);
        RuleVersion rule = ruleService.requireVersion(version);
        return evaluateWithRule(cleanAddress, validAsOf, rule, true, Long.MAX_VALUE);
    }

    private RiskConclusion evaluateWithRule(String address, long asOf, RuleVersion rule,
                                            boolean pinnedVersion, long maxSeq) {
        List<RiskLabel> labels = labelRegistry.effectiveLabels(asOf, maxSeq);
        GraphView view = buildSnapshot(asOf, maxSeq);

        List<RiskPath> contributions = new ArrayList<>();
        for (RiskLabel label : labels) {
            Map<String, RiskPath> reachable = propagateFrom(label, rule, view);
            RiskPath path = reachable.get(address);
            if (path != null) {
                contributions.add(path);
            }
        }
        contributions.sort(Comparator.comparingDouble(RiskPath::contribution).reversed()
                .thenComparing(RiskPath::sourceAddress));

        double score = aggregate(contributions);
        boolean risky = score >= rule.riskThreshold();
        RiskConclusion conclusion = new RiskConclusion(address, asOf, score, risky,
                rule.version(), rule.riskThreshold(), List.copyOf(contributions));

        logConclusion(conclusion, pinnedVersion);
        return conclusion;
    }

    /** 某时点全部相关地址（图节点 ∪ 风险源）的风险结论，按地址字典序（默认时间点语义） */
    public List<RiskConclusion> evaluateAll(long asOf) {
        long validAsOf = QueryValidator.requireAsOf(asOf);
        RuleVersion rule = ruleService.requireRuleAt(validAsOf);
        return evaluateAllWithRule(validAsOf, rule, false, Long.MAX_VALUE);
    }

    /** 批量历史回放专用：全量评估，数据与规则都限制在提交水位 {@code maxSeq}（含）之前 */
    public List<RiskConclusion> evaluateAllFenced(long asOf, long maxSeq) {
        long validAsOf = QueryValidator.requireAsOf(asOf);
        RuleVersion rule = ruleService.requireRuleAt(validAsOf, maxSeq);
        return evaluateAllWithRule(validAsOf, rule, false, maxSeq);
    }

    /** 某时点全部相关地址的风险结论，显式指定规则版本复现 */
    public List<RiskConclusion> evaluateAll(long asOf, int version) {
        long validAsOf = QueryValidator.requireAsOf(asOf);
        RuleVersion rule = ruleService.requireVersion(version);
        return evaluateAllWithRule(validAsOf, rule, true, Long.MAX_VALUE);
    }

    private List<RiskConclusion> evaluateAllWithRule(long asOf, RuleVersion rule,
                                                     boolean pinnedVersion, long maxSeq) {
        List<RiskLabel> labels = labelRegistry.effectiveLabels(asOf, maxSeq);
        GraphView view = buildSnapshot(asOf, maxSeq);

        // 预先计算每个风险源的可达结果，避免重复搜索
        Map<String, Map<String, RiskPath>> bySource = new LinkedHashMap<>();
        for (RiskLabel label : labels) {
            bySource.put(label.address(), propagateFrom(label, rule, view));
        }
        Set<String> targets = new TreeSet<>(view.nodes);
        labels.forEach(l -> targets.add(l.address()));

        List<RiskConclusion> all = new ArrayList<>();
        for (String address : targets) {
            List<RiskPath> contributions = new ArrayList<>();
            for (Map.Entry<String, Map<String, RiskPath>> e : bySource.entrySet()) {
                RiskPath path = e.getValue().get(address);
                if (path != null) {
                    contributions.add(path);
                }
            }
            contributions.sort(Comparator.comparingDouble(RiskPath::contribution).reversed()
                    .thenComparing(RiskPath::sourceAddress));
            double score = aggregate(contributions);
            all.add(new RiskConclusion(address, asOf, score, score >= rule.riskThreshold(),
                    rule.version(), rule.riskThreshold(), List.copyOf(contributions)));
        }
        log.info("快照 t={} 全量风险评估完成，地址数={}, 规则=v{}{}", asOf, all.size(),
                rule.version(), pinnedVersion ? "(显式指定回放)" : "(时间点自动选取)");
        return List.copyOf(all);
    }

    /**
     * 从单个风险源出发 BFS。访问集保证每个节点在本风险源下只落一次最优贡献，
     * 自环/环路直接被剪掉。
     */
    private Map<String, RiskPath> propagateFrom(RiskLabel source, RuleVersion rule, GraphView view) {
        Map<String, RiskPath> best = new HashMap<>();
        Set<String> visited = new HashSet<>();
        ArrayDeque<State> queue = new ArrayDeque<>();

        List<String> originRoute = List.of(source.address());
        best.put(source.address(), new RiskPath(source.address(), source.baseScore(), 0,
                source.baseScore(), originRoute, List.of()));
        visited.add(source.address());
        queue.add(new State(source.address(), 0, originRoute, List.of()));

        while (!queue.isEmpty()) {
            State cur = queue.poll();
            if (cur.hops >= rule.maxHops()) {
                continue;
            }
            List<Edge> edges = view.outgoing.getOrDefault(cur.node, List.of());
            for (Edge edge : edges) {
                if (!visited.add(edge.to())) {
                    // 已访问过（含自环、回到环路中的节点）：不累加、不入队
                    continue;
                }
                int hops = cur.hops + 1;
                double contribution = source.baseScore() * Math.pow(rule.decay(), hops);
                // 浮点误差裁剪到 [0,1]
                contribution = Math.min(1.0d, Math.max(0.0d, contribution));
                List<String> route = new ArrayList<>(cur.route);
                route.add(edge.to());
                List<String> txIds = new ArrayList<>(cur.txIds);
                txIds.add(edge.txId());
                List<String> frozenRoute = List.copyOf(route);
                List<String> frozenTxIds = List.copyOf(txIds);
                RiskPath path = new RiskPath(source.address(), source.baseScore(), hops,
                        contribution, frozenRoute, frozenTxIds);
                best.put(edge.to(), path);
                queue.add(new State(edge.to(), hops, frozenRoute, frozenTxIds));
            }
        }
        return best;
    }

    /** noisy-OR 聚合多个（来自不同风险源的）独立贡献 */
    private static double aggregate(List<RiskPath> contributions) {
        double remaining = 1.0d;
        for (RiskPath p : contributions) {
            remaining *= (1.0d - p.contribution());
        }
        double score = 1.0d - remaining;
        return Math.min(1.0d, Math.max(0.0d, score));
    }

    private void logConclusion(RiskConclusion c, boolean pinnedVersion) {
        log.info("风险判定 address={}, asOf={}, score={}, risky={}, 规则=v{}{}, 阈值={}",
                c.address(), c.asOf(), fmt(c.riskScore()), c.risky(),
                c.ruleVersion(), pinnedVersion ? "(显式指定回放)" : "(时间点自动选取)",
                c.riskThreshold());
        if (c.contributions().isEmpty()) {
            log.info("  判定依据: 地址 {} 在 t={} 无任何可达风险源，score=0", c.address(), c.asOf());
        }
        for (RiskPath p : c.contributions()) {
            log.info("  贡献来源={}, baseScore={}, 跳数={}, 贡献={}, 传播路径={}, 交易序列={}",
                    p.sourceAddress(), fmt(p.sourceBaseScore()), p.hops(), fmt(p.contribution()),
                    String.join(" -> ", p.route()), String.join(" -> ", p.txIds()));
        }
        log.info("  判定依据: noisy-OR 聚合 {} 条路径贡献 => score={} {} 阈值 {}",
                c.contributions().size(), fmt(c.riskScore()),
                c.risky() ? ">=" : "<", c.riskThreshold());
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }

    private record State(String node, int hops, List<String> route, List<String> txIds) {
    }

    /**
     * 快照图视图（不可变）。
     */
    public static final class GraphView {
        public final long asOf;
        public final List<Transaction> transactions;
        public final Map<String, List<Edge>> outgoing;
        public final List<String> nodes;

        public GraphView(long asOf, List<Transaction> transactions,
                         Map<String, List<Edge>> outgoing, List<String> nodes) {
            this.asOf = asOf;
            this.transactions = transactions;
            this.outgoing = outgoing;
            this.nodes = nodes;
        }
    }

    /** 图的一条出边 */
    public record Edge(String txId, String from, String to, long amount) {
    }
}
