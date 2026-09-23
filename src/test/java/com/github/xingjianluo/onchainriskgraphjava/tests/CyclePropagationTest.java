package com.github.xingjianluo.onchainriskgraphjava.tests;

import com.github.xingjianluo.onchainriskgraphjava.ledger.TransactionLedger;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskConclusion;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskLabel;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskPath;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitStatus;
import com.github.xingjianluo.onchainriskgraphjava.model.Transaction;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskLabelRegistry;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskPropagationService;
import com.github.xingjianluo.onchainriskgraphjava.service.RuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自环与环路传播测试：不得无限传播或重复累加，路径可回溯。
 *
 * <pre>
 *   A(风险源 baseScore=1.0)
 *     --e1--> B --e2--> C
 *               ^         |
 *               +---e3----+   (B->C->B 环)
 *   B --e4--> B                (B 自环)
 * </pre>
 * 规则：maxHops=2, decay=0.5, threshold=0.5
 */
class CyclePropagationTest {

    private static final Logger log = LoggerFactory.getLogger(CyclePropagationTest.class);

    private TransactionLedger ledger;
    private RiskLabelRegistry labels;
    private RiskPropagationService propagation;

    @BeforeEach
    void setUp() {
        ledger = new TransactionLedger();
        labels = new RiskLabelRegistry();
        RuleService rules = new RuleService();
        propagation = new RiskPropagationService(ledger, labels, rules);

        assertEquals(SubmitStatus.ACCEPTED,
                rules.publish(2, 0.5, 0.5, 0L).status());
        assertEquals(SubmitStatus.ACCEPTED,
                labels.register(new RiskLabel("A", 1.0, 0L)).status());
        // 主链
        accept(new Transaction("e1", "A", "B", 100, 100L));
        accept(new Transaction("e2", "B", "C", 60, 200L));
        // 环路回边 C -> B
        accept(new Transaction("e3", "C", "B", 30, 300L));
        // 自环 B -> B
        accept(new Transaction("e4", "B", "B", 10, 400L));
    }

    private void accept(Transaction tx) {
        assertEquals(SubmitStatus.ACCEPTED, ledger.submit(tx).status());
    }

    @Test
    void selfLoopAndCycle_neverAccumulateAndPathIsTraceable() {
        log.info("========== 交易流水（含自环 e4、环路 e2/e3）==========");
        ledger.visibleTransactions(Long.MAX_VALUE).forEach(t ->
                log.info("  txId={}, {} -> {}, amount={}, visibleAt={}",
                        t.txId(), t.fromAddress(), t.toAddress(), t.amount(), t.visibleAt()));

        RiskConclusion a = propagation.evaluate("A", 1000L);
        RiskConclusion b = propagation.evaluate("B", 1000L);
        RiskConclusion c = propagation.evaluate("C", 1000L);

        // A 自身：跳数 0，贡献 1.0
        assertEquals(1.0, a.riskScore(), 1e-9);
        assertTrue(a.risky());

        // B：A->B 一跳 0.5；自环 e4 被剪；C->B 回边因 B 已访问被剪，不累加
        assertEquals(0.5, b.riskScore(), 1e-9);
        assertTrue(b.risky());
        assertEquals(1, b.contributions().size(), "每个风险源只保留一条最大贡献路径");
        RiskPath bPath = b.contributions().get(0);
        assertEquals(1, bPath.hops());
        assertEquals(java.util.List.of("A", "B"), bPath.route());
        assertEquals(java.util.List.of("e1"), bPath.txIds());

        // C：A->B->C 两跳 0.25；沿 C->B->C 的环路不会再产生贡献
        assertEquals(0.25, c.riskScore(), 1e-9);
        assertFalse(c.risky(), "0.25 未达阈值 0.5");
        RiskPath cPath = c.contributions().get(0);
        assertEquals(2, cPath.hops());
        assertEquals(java.util.List.of("A", "B", "C"), cPath.route());
        assertEquals(java.util.List.of("e1", "e2"), cPath.txIds());

        log.info("========== 重复运行验证结论稳定（环路无累加）==========");
        RiskConclusion bAgain = propagation.evaluate("B", 1000L);
        RiskConclusion cAgain = propagation.evaluate("C", 1000L);
        assertEquals(b.riskScore(), bAgain.riskScore(), 1e-12);
        assertEquals(c.riskScore(), cAgain.riskScore(), 1e-12);
        log.info("重复计算 B.score={}, C.score={}，结论一致", bAgain.riskScore(), cAgain.riskScore());
    }
}
