package com.github.xingjianluo.onchainriskgraphjava.tests;

import com.github.xingjianluo.onchainriskgraphjava.ledger.TransactionLedger;
import com.github.xingjianluo.onchainriskgraphjava.model.FundFlow;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskConclusion;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskLabel;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskPath;
import com.github.xingjianluo.onchainriskgraphjava.model.RuleVersion;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitResult;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitStatus;
import com.github.xingjianluo.onchainriskgraphjava.model.Transaction;
import com.github.xingjianluo.onchainriskgraphjava.service.FundFlowService;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskLabelRegistry;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskPropagationService;
import com.github.xingjianluo.onchainriskgraphjava.service.RuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快照回放、规则版本一致性与多源聚合测试。
 *
 * <pre>
 *   X(风险源 base=1.0, t=0) --t1(at100)--> Y --t2(at200)--> Z
 *   W(风险源 base=0.8, t=250) -----------t3(at300)-------> Z
 * </pre>
 * 规则 v1: maxHops=2, decay=0.5, threshold=0.5, effectiveAt=0
 * 规则 v2: threshold=0.3, effectiveAt=500（只影响 t>=500 的结论）
 */
class SnapshotReplayTest {

    private static final Logger log = LoggerFactory.getLogger(SnapshotReplayTest.class);

    private TransactionLedger ledger;
    private RuleService rules;
    private RiskLabelRegistry labels;
    private RiskPropagationService propagation;
    private FundFlowService funds;

    @BeforeEach
    void setUp() {
        ledger = new TransactionLedger();
        rules = new RuleService();
        labels = new RiskLabelRegistry();
        propagation = new RiskPropagationService(ledger, labels, rules);
        funds = new FundFlowService(propagation);

        rules.publish(2, 0.5, 0.5, 0L);
        labels.register(new RiskLabel("X", 1.0, 0L));
        ledger.submit(new Transaction("t1", "X", "Y", 100, 100L));
        ledger.submit(new Transaction("t2", "Y", "Z", 60, 200L));
        labels.register(new RiskLabel("W", 0.8, 250L));
        ledger.submit(new Transaction("t3", "W", "Z", 40, 300L));
    }

    @Test
    void snapshotOnlyReflectsVisibleTransactions() {
        // t=150：只可见 t1；风险源 W 尚未生效；t2/t3 尚不可见
        RiskConclusion zAt150 = propagation.evaluate("Z", 150L);
        assertEquals(0.0, zAt150.riskScore(), 1e-9);
        assertFalse(zAt150.risky());

        RiskConclusion yAt150 = propagation.evaluate("Y", 150L);
        assertEquals(0.5, yAt150.riskScore(), 1e-9);
        assertTrue(yAt150.risky());

        FundFlow zFlow150 = funds.summarize("Z", 150L);
        assertEquals(0L, zFlow150.totalInflow());

        // t=350：全部可见。Z 从 X 两跳贡献 0.25，从 W 一跳贡献 0.4
        RiskConclusion zAt350 = propagation.evaluate("Z", 350L);
        assertEquals(2, zAt350.contributions().size());
        double expected = 1.0 - (1.0 - 0.4) * (1.0 - 0.25); // noisy-OR = 0.55
        assertEquals(expected, zAt350.riskScore(), 1e-9);
        assertTrue(zAt350.risky());

        FundFlow zFlow350 = funds.summarize("Z", 350L);
        assertEquals(100L, zFlow350.totalInflow(), "t2(60)+t3(40)=100");
        assertEquals(List.of("t2", "t3"), zFlow350.inflowTxIds());

        log.info("========== 快照隔离：t=150 与 t=350 的 Z 结论 ==========");
        log.info("Z@150 score={} (t2/t3 尚不可见, W 未生效)", zAt150.riskScore());
        for (RiskPath p : zAt350.contributions()) {
            log.info("Z@350 贡献: 源={}, 跳数={}, 贡献={}, 路径={}, tx={}",
                    p.sourceAddress(), p.hops(), p.contribution(),
                    String.join(" -> ", p.route()), String.join(" -> ", p.txIds()));
        }
        log.info("Z@350 noisy-OR score={}, risky={}", zAt350.riskScore(), zAt350.risky());

        // 之后再写入 t=600 的交易，不影响历史快照
        ledger.submit(new Transaction("t4", "Z", "Q", 999, 600L));
        RiskConclusion zAt150Again = propagation.evaluate("Z", 150L);
        RiskConclusion zAt350Again = propagation.evaluate("Z", 350L);
        assertEquals(0.0, zAt150Again.riskScore(), 1e-12);
        assertEquals(expected, zAt350Again.riskScore(), 1e-12);
        assertEquals(100L, funds.summarize("Z", 350L).totalInflow());
        log.info("t4(visibleAt=600) 写入后重放 t=150/350，结论不变（快照隔离成立）");
    }

    @Test
    void ruleChangesReproduceHistoricalConclusions_butDoNotRewritePast() {
        RiskConclusion before = propagation.evaluate("Z", 350L);
        assertEquals(1, before.ruleVersion());
        assertTrue(before.risky(), "v1 阈值 0.5 下 score=0.55 为高风险");

        // 发布 v2：阈值降到 0.3，effectiveAt=500
        SubmitResult<RuleVersion> pub = rules.publish(2, 0.5, 0.3, 500L);
        assertEquals(SubmitStatus.ACCEPTED, pub.status());

        // 历史时点仍按 v1 复现
        RiskConclusion replay = propagation.evaluate("Z", 350L);
        assertEquals(1, replay.ruleVersion());
        assertEquals(0.5, replay.riskThreshold(), 1e-9);
        assertTrue(replay.risky());

        // 新时点使用 v2（阈值更低）
        RiskConclusion after = propagation.evaluate("Y", 500L);
        assertEquals(2, after.ruleVersion());
        assertEquals(0.3, after.riskThreshold(), 1e-9);

        log.info("========== 规则版本一致性 ==========");
        log.info("Z@350 按 v1(threshold=0.5) 复现: score={}, risky={}",
                replay.riskScore(), replay.risky());
        log.info("Y@500 按 v2(threshold=0.3) 判定: score={}, risky={}, ruleVersion={}",
                after.riskScore(), after.risky(), after.ruleVersion());
    }

    @Test
    void invalidOrConflictingRules_neverTakeEffect() {
        SubmitResult<RuleVersion> invalid = rules.publish(-1, 0.5, 0.5, 400L);
        SubmitResult<RuleVersion> invalidDecay = rules.publish(2, 1.5, 0.5, 400L);
        // effectiveAt 必须严格递增：400 > 0 合法，但再来一个 400 即冲突
        rules.publish(2, 0.5, 0.3, 400L);
        SubmitResult<RuleVersion> conflict = rules.publish(2, 0.5, 0.2, 400L);

        assertEquals(SubmitStatus.REJECTED_INVALID, invalid.status());
        assertEquals(SubmitStatus.REJECTED_INVALID, invalidDecay.status());
        assertEquals(SubmitStatus.REJECTED_CONFLICT, conflict.status());

        // 非法/冲突均不改变版本线：当前最新仍是 v2(effectiveAt=400)
        RuleVersion current = rules.ruleAt(999L).orElseThrow();
        assertEquals(2, current.version());
        assertEquals(0.3, current.riskThreshold(), 1e-9);
        log.info("非法规则: {}, 冲突规则: {}；均未生效，当前 v{} threshold={}",
                invalid.message(), conflict.message(), current.version(), current.riskThreshold());

        // 没有任何规则覆盖的时点，评估必须显式失败而不是给静默结论
        RuleService emptyRules = new RuleService();
        RiskPropagationService emptyProp = new RiskPropagationService(
                new TransactionLedger(), new RiskLabelRegistry(), emptyRules);
        assertThrows(NoSuchElementException.class, () -> emptyProp.evaluate("X", 0L));
    }
}
