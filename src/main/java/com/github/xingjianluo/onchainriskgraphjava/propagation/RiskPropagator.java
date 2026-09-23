package com.github.xingjianluo.onchainriskgraphjava.propagation;

import com.github.xingjianluo.onchainriskgraphjava.domain.PropagationHop;
import com.github.xingjianluo.onchainriskgraphjava.domain.RiskConclusion;
import com.github.xingjianluo.onchainriskgraphjava.domain.RiskLabel;
import com.github.xingjianluo.onchainriskgraphjava.domain.RiskPath;
import com.github.xingjianluo.onchainriskgraphjava.domain.Transaction;
import com.github.xingjianluo.onchainriskgraphjava.rule.RiskRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 风险传播引擎（无状态、纯计算，输入是某快照下可见的流水与标记）。
 *
 * 模型：
 * - 资金沿交易方向流动：from =风险资金=> to。评估某地址 X 时，反向沿资金流向
 *   回溯其上游（谁给 X 转过账、谁给上游转过账……）寻找已标记的风险源头。
 * - 某源头经 k 跳到达 X 的单路径贡献 = baseScore * decay^k。
 * - 一个源头只取其贡献最大的一条路径（衰减相同 => 最短路径），故环路/自环
 *   不会重复累加；BFS 的 visited 集合保证同一节点在一次传播中至多扩展一次，
 *   环路不会造成无限传播。
 * - 多个相互独立的源头，按概率噪声或(noisy-or)聚合：
 *   score = 1 - Π(1 - contribution_i)，避免线性相加超过 1 与重复计数。
 * - 结论 risk(y/n) 取决于 score 是否达到规则阈值。
 */
@Component
public class RiskPropagator {

    private static final Logger log = LoggerFactory.getLogger(RiskPropagator.class);

    /**
     * @param address             被评估地址
     * @param asOf                快照时点
     * @param visibleTransactions 该时点可见交易（已按时间/txId 排序）
     * @param visibleLabels       该时点可见风险标记（每地址一条）
     * @param rule                该时点生效规则（或调用方显式指定的版本）
     */
    public RiskConclusion evaluate(String address,
                                   long asOf,
                                   List<Transaction> visibleTransactions,
                                   List<RiskLabel> visibleLabels,
                                   RiskRule rule) {
        Map<String, RiskLabel> labelByAddress = new HashMap<>();
        visibleLabels.forEach(l -> labelByAddress.put(l.address(), l));

        // 反向邻接表：toAddress -> 流入该地址的交易列表（资金来向）。
        // 同一对地址的多笔交易构成多条边，逐条按 (timestamp, txId) 排序，保证遍历确定性。
        Map<String, List<Transaction>> incoming = new LinkedHashMap<>();
        for (Transaction tx : visibleTransactions) {
            incoming.computeIfAbsent(tx.toAddress(), k -> new ArrayList<>()).add(tx);
        }

        // BFS 状态
        Map<String, BfsNode> visited = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        visited.put(address, new BfsNode(address, 0, null, null));
        queue.add(address);

        List<RiskPath> paths = new ArrayList<>();

        while (!queue.isEmpty()) {
            String current = queue.poll();
            BfsNode node = visited.get(current);

            // 到达一个风险源头 => 记录一条贡献路径（目标自身被标记时 hop=0）
            RiskLabel source = labelByAddress.get(current);
            if (source != null) {
                paths.add(buildPath(address, node, source, rule.decay()));
            }
            if (node.hops >= rule.maxHops()) {
                continue; // 超过最大跳数，不再向上游扩展（但本节点若是源头其 0..maxHops 路径已记录）
            }

            List<Transaction> edges = incoming.get(current);
            if (edges == null) {
                continue;
            }
            for (Transaction edge : edges) {
                String upstream = edge.fromAddress();
                // 自环：资金从地址转回自身，visited 命中后直接忽略，不产生额外跳与累加
                if (visited.containsKey(upstream)) {
                    if (upstream.equals(current)) {
                        log.info("[传播路径] 忽略自环 txId={} address={}（不重复累加）",
                                edge.txId(), upstream);
                    }
                    continue;
                }
                visited.put(upstream, new BfsNode(upstream, node.hops + 1, node, edge));
                queue.add(upstream);
            }
        }

        // 贡献大的路径排在前面，便于解释；并列按源头地址确定排序
        paths.sort(Comparator.comparingDouble(RiskPath::contribution).reversed()
                .thenComparing(RiskPath::sourceAddress));

        double score = aggregate(paths);
        double rounded = round3(score);
        boolean risky = rounded >= rule.threshold();

        log.info("[风险结论] address={} asOf={} rule={} score={} threshold={} risky={} 贡献来源数={}",
                address, asOf, rule.version(), rounded, rule.threshold(), risky, paths.size());
        for (RiskPath p : paths) {
            log.info("[判定依据] 源头={} baseScore={} 跳数={} 贡献={} 路径={}",
                    p.sourceAddress(), p.baseScore(), p.hopCount(),
                    round3(p.contribution()), renderPath(p));
        }

        return new RiskConclusion(address, rounded, risky, asOf, rule.version(),
                rule.threshold(), List.copyOf(paths));
    }

    /**
     * 由 BFS 前驱链重建一条从源头到目标的传播路径。
     * 反向 BFS 中某节点的 via 是资金方向上“流入该节点”的边，
     * 因此沿前驱回溯时先拿到的是离源头最近的边，顺序天然为 源头->目标。
     */
    private RiskPath buildPath(String target, BfsNode targetNode, RiskLabel source, double decay) {
        List<PropagationHop> hops = new ArrayList<>();
        BfsNode cur = targetNode;
        while (cur.parent != null) {
            Transaction e = cur.via;
            hops.add(new PropagationHop(
                    e.txId(), e.fromAddress(), e.toAddress(), e.timestamp(),
                    hops.size() + 1,
                    round3(source.baseScore() * Math.pow(decay, hops.size() + 1))));
            cur = cur.parent;
        }
        int k = hops.size();
        double contribution = round3(source.baseScore() * Math.pow(decay, k));
        return new RiskPath(source.address(), source.baseScore(),
                List.copyOf(hops), contribution);
    }

    /** noisy-or 聚合独立来源贡献。 */
    private double aggregate(List<RiskPath> paths) {
        double remaining = 1.0d;
        for (RiskPath p : paths) {
            remaining *= (1.0d - clamp(p.contribution()));
        }
        return 1.0d - remaining;
    }

    private static String renderPath(RiskPath p) {
        StringBuilder sb = new StringBuilder(p.sourceAddress());
        for (PropagationHop h : p.hops()) {
            sb.append(" --").append(h.txId()).append("(").append(h.edgeScore()).append(")--> ")
              .append(h.toAddress());
        }
        return sb.toString();
    }

    private static double clamp(double v) {
        return Math.max(0d, Math.min(1d, v));
    }

    private static double round3(double v) {
        return Math.round(clamp(v) * 1000d) / 1000d;
    }

    /** BFS 节点：地址、距目标跳数、前驱节点、入边交易。 */
    private static final class BfsNode {
        final String address;
        final int hops;
        final BfsNode parent;
        final Transaction via;

        BfsNode(String address, int hops, BfsNode parent, Transaction via) {
            this.address = address;
            this.hops = hops;
            this.parent = parent;
            this.via = via;
        }
    }
}
