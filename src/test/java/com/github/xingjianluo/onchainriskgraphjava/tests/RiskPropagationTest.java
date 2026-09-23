package com.github.xingjianluo.onchainriskgraphjava.tests;

import com.github.xingjianluo.onchainriskgraphjava.domain.RiskConclusion;
import com.github.xingjianluo.onchainriskgraphjava.domain.RiskLabel;
import com.github.xingjianluo.onchainriskgraphjava.domain.RiskPath;
import com.github.xingjianluo.onchainriskgraphjava.domain.Transaction;
import com.github.xingjianluo.onchainriskgraphjava.propagation.RiskPropagator;
import com.github.xingjianluo.onchainriskgraphjava.rule.RiskRule;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖：按跳衰减传播、最大跳数截断、环路/自环不无限传播且不重复累加、
 * 多来源 noisy-or 聚合、传播路径与贡献可回溯。
 * 运行时日志以 [传播路径]/[判定依据] 前缀打印路径与依据。
 */
class RiskPropagationTest {

    private static final Logger log = LoggerFactory.getLogger(RiskPropagationTest.class);
    private final RiskPropagator propagator = new RiskPropagator();

    private final RiskRule rule = new RiskRule("test-v1", 3, 0.5d, 0.5d, 0L, "测试规则");

    private Transaction tx(String id, String from, String to, long ts) {
        return new Transaction(id, from, to, new BigDecimal("10"), ts);
    }

    private RiskLabel label(String address, double score) {
        return new RiskLabel(address, score, "test-seed", 0L);
    }

    @Test
    void cycleAndSelfLoop_doNotInflateOrLoopForever() {
        // 资金流：S -> A -> B -> A（A-B-A 成环），另有 A -> A 自环
        List<Transaction> txs = List.of(
                tx("e1", "S", "A", 100L),
                tx("e2", "A", "B", 200L),
                tx("e3", "B", "A", 300L), // 环路边
                tx("e4", "A", "A", 400L)  // 自环
        );
        List<RiskLabel> labels = List.of(label("S", 1.0d));

        RiskConclusion cB = propagator.evaluate("B", 500L, txs, labels, rule);

        // 唯一来源 S，2 跳：1.0 * 0.5^2 = 0.25；环路不产生第二条贡献路径
        assertEquals(0.25d, cB.score(), 1e-9);
        assertEquals(1, cB.paths().size(), "环路/自环不得制造额外贡献路径");
        RiskPath path = cB.paths().get(0);
        assertEquals("S", path.sourceAddress());
        assertEquals(2, path.hopCount());
        assertEquals(List.of("e1", "e2"), path.hops().stream().map(h -> h.txId()).toList());
        assertEquals(0.25d, path.contribution(), 1e-9);
        assertFalse(cB.risky(), "0.25 低于阈值 0.5");
        log.info("[测试断言] 环路场景 B 的路径数=1, score=0.25，环路边 e3/自环 e4 被忽略");
    }

    @Test
    void hopDecayAndMaxHopsTruncation() {
        // S -> A -> B -> C -> D，maxHops=3：D(4跳)收不到风险
        List<Transaction> txs = List.of(
                tx("t1", "S", "A", 1L),
                tx("t2", "A", "B", 2L),
                tx("t3", "B", "C", 3L),
                tx("t4", "C", "D", 4L)
        );
        List<RiskLabel> labels = List.of(label("S", 1.0d));

        assertEquals(0.125d, propagator.evaluate("C", 10L, txs, labels, rule).score(), 1e-9); // 3 跳
        RiskConclusion d = propagator.evaluate("D", 10L, txs, labels, rule);
        assertEquals(0.0d, d.score(), 1e-9); // 4 跳 > maxHops
        assertTrue(d.paths().isEmpty());
        assertFalse(d.risky());
        log.info("[测试断言] C=3跳 score=0.125；D=4跳超过 maxHops=3，score=0");
    }

    @Test
    void multipleSourcesAggregateViaNoisyOrWithoutDoubleCount() {
        // S1 -> X，S2 -> Y -> X；X 有两个独立来源
        List<Transaction> txs = List.of(
                tx("m1", "S1", "X", 1L),
                tx("m2", "S2", "Y", 1L),
                tx("m3", "Y", "X", 2L)
        );
        List<RiskLabel> labels = List.of(label("S1", 0.6d), label("S2", 0.8d));

        RiskConclusion x = propagator.evaluate("X", 10L, txs, labels, rule);
        double c1 = 0.6d * 0.5d;          // 0.30
        double c2 = 0.8d * 0.5d * 0.5d;   // 0.20
        double expected = 1 - (1 - c1) * (1 - c2); // 0.44
        assertEquals(Math.round(expected * 1000d) / 1000d, x.score(), 1e-9);
        assertEquals(2, x.paths().size());
        // 按贡献降序
        assertEquals("S1", x.paths().get(0).sourceAddress());
        assertEquals("S2", x.paths().get(1).sourceAddress());
        log.info("[测试断言] X 双来源 noisy-or: c1=0.30, c2=0.20, score={}", x.score());
    }

    @Test
    void labeledTargetItselfIsZeroHopRisk() {
        RiskConclusion s = propagator.evaluate("S", 10L, List.of(), List.of(label("S", 0.9d)), rule);
        assertEquals(0.9d, s.score(), 1e-9);
        assertTrue(s.risky());
        assertEquals(0, s.paths().get(0).hopCount());
        log.info("[测试断言] 被标记地址自身 0 跳 score=0.9 risky=true");
    }
}
