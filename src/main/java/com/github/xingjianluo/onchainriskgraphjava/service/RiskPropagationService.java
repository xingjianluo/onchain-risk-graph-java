package com.github.xingjianluo.onchainriskgraphjava.service;

import com.github.xingjianluo.onchainriskgraphjava.ledger.TransactionLedger;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskConclusion;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskLabel;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskPath;
import com.github.xingjianluo.onchainriskgraphjava.model.RuleVersion;
import com.github.xingjianluo.onchainriskgraphjava.model.Transaction;
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
import java.util.NoSuchElementException;
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

    /** 构建仅含 asOf（含）之前可见交易的不可变图视图 */
    public GraphView buildSnapshot(long asOf) {
        List<Transaction> txs = ledger.visibleTransactions(asOf);
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

    /** 在 asOf 时点的快照上评估某地址风险 */
    public RiskConclusion evaluate(String address, long asOf) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("address 不能为空");
        }
        RuleVersion rule = ruleAt(asOf);
        List<RiskLabel> labels = labelRegistry.effectiveLabels(asOf);
        GraphView view = buildSnapshot(asOf);

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

        logConclusion(conclusion);
        return conclusion;
    }

    /** 某时点全部相关地址（图节点 ∪ 风险源）的风险结论，按地址字典序 */
    public List<RiskConclusion> evaluateAll(long asOf) {
        RuleVersion rule = ruleAt(asOf);
        List<RiskLabel> labels = labelRegistry.effectiveLabels(asOf);
        GraphView view = buildSnapshot(asOf);

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
        log.info("快照 t={} 全量风险评估完成，地址数={}, 规则=v{}", asOf, all.size(), rule.version());
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

    private RuleVersion ruleAt(long asOf) {
        return ruleService.ruleAt(asOf).orElseThrow(() -> new NoSuchElementException(
                "时间点 " + asOf + " 没有可用的风险规则版本，无法评估"));
    }

    private void logConclusion(RiskConclusion c) {
        log.info("风险判定 address={}, asOf={}, score={}, risky={}, 规则=v{}, 阈值={}",
                c.address(), c.asOf(), fmt(c.riskScore()), c.risky(),
                c.ruleVersion(), c.riskThreshold());
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
