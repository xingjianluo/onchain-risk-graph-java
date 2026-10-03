package com.github.xingjianluo.onchainriskgraphjava.tests;

import com.github.xingjianluo.onchainriskgraphjava.ledger.TransactionLedger;
import com.github.xingjianluo.onchainriskgraphjava.model.QueryOutcome;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskConclusion;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskLabel;
import com.github.xingjianluo.onchainriskgraphjava.model.RuleDiffResult;
import com.github.xingjianluo.onchainriskgraphjava.model.RuleVersion;
import com.github.xingjianluo.onchainriskgraphjava.model.ScoreChange;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitStatus;
import com.github.xingjianluo.onchainriskgraphjava.model.Transaction;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskLabelRegistry;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskPropagationService;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskReplayService;
import com.github.xingjianluo.onchainriskgraphjava.service.RuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则版本回放与影响对比测试。
 *
 * <pre>
 *   X(风险源 base=1.0, t=0) --t1(at100)--> Y --t2(at200)--> Z
 * </pre>
 * 规则 v1: maxHops=2, decay=0.5, threshold=0.5, effectiveAt=0
 * 规则 v2: threshold=0.2, effectiveAt=500（同参数只降阈值）
 * 规则 v3: decay=0.4, threshold=0.5, effectiveAt=800（只改衰减）
 *
 * t=350 时 Z 的两跳贡献：v1/v2 下 0.25，v3 下 0.16。
 */
class RuleVersionReplayTest {

    private static final Logger log = LoggerFactory.getLogger(RuleVersionReplayTest.class);

    private RiskPropagationService propagation;
    private RiskReplayService replayService;
    private RuleService rules;

    @BeforeEach
    void setUp() {
        TransactionLedger ledger = new TransactionLedger();
        rules = new RuleService();
        RiskLabelRegistry labels = new RiskLabelRegistry();
        propagation = new RiskPropagationService(ledger, labels, rules);
        replayService = new RiskReplayService(propagation, rules);

        assertEquals(SubmitStatus.ACCEPTED, rules.publish(2, 0.5, 0.5, 0L).status());
        assertEquals(SubmitStatus.ACCEPTED, labels.register(new RiskLabel("X", 1.0, 0L)).status());
        assertEquals(SubmitStatus.ACCEPTED,
                ledger.submit(new Transaction("t1", "X", "Y", 100, 100L)).status());
        assertEquals(SubmitStatus.ACCEPTED,
                ledger.submit(new Transaction("t2", "Y", "Z", 60, 200L)).status());
    }

    @Test
    void explicitOldVersion_reproducesHistoricalConclusion_andIsNotBiasedByLaterRules() {
        // 历史结论：v1 生效期间 t=350 的 Z（两跳贡献 0.25，阈值 0.5 => 非高风险）
        RiskConclusion historical = propagation.evaluate("Z", 350L);
        assertEquals(1, historical.ruleVersion());
        assertEquals(0.25, historical.riskScore(), 1e-9);
        assertFalse(historical.risky());
        log.info("历史结论 Z@350: score={}, risky={}, ruleVersion=v{}",
                historical.riskScore(), historical.risky(), historical.ruleVersion());

        // 之后连续发布 v2、v3
        assertEquals(SubmitStatus.ACCEPTED, rules.publish(2, 0.5, 0.2, 500L).status());
        assertEquals(SubmitStatus.ACCEPTED, rules.publish(2, 0.4, 0.5, 800L).status());

        // 显式指定 v1 复现：必须与历史结论逐位一致，不被 v2/v3 带偏
        QueryOutcome<RiskConclusion> replayV1 = propagation.evaluateOutcome("Z", 350L, 1, null);
        assertTrue(replayV1.isOk());
        RiskConclusion r1 = replayV1.payload();
        assertEquals(historical.ruleVersion(), r1.ruleVersion());
        assertEquals(historical.riskScore(), r1.riskScore(), 1e-12);
        assertEquals(historical.risky(), r1.risky());
        assertEquals(historical.riskThreshold(), r1.riskThreshold(), 1e-12);
        assertEquals(historical.contributions(), r1.contributions());
        log.info("显式 v1 复现 Z@350: score={}, risky={} —— 与历史结论一致", r1.riskScore(), r1.risky());

        // 显式指定 v2 复现同一时点：用 v2 的阈值 0.2 判定 => 翻转为高风险（证明确实用了 v2 参数）
        QueryOutcome<RiskConclusion> replayV2 = propagation.evaluateOutcome("Z", 350L, 2, null);
        assertTrue(replayV2.isOk());
        assertEquals(2, replayV2.payload().ruleVersion());
        assertEquals(0.25, replayV2.payload().riskScore(), 1e-9);
        assertTrue(replayV2.payload().risky(), "v2 阈值 0.2 下 0.25 应判高风险");
        log.info("显式 v2 复现 Z@350: score={}, risky={} —— 阈值 0.2 生效",
                replayV2.payload().riskScore(), replayV2.payload().risky());

        // 不指定版本：仍按时间点语义走（t=350 只有 v1 生效，不受后发布规则影响）
        QueryOutcome<RiskConclusion> byTime = propagation.evaluateOutcome("Z", 350L, null, null);
        assertTrue(byTime.isOk());
        assertEquals(1, byTime.payload().ruleVersion());
        assertEquals(historical.riskScore(), byTime.payload().riskScore(), 1e-12);
        assertEquals(historical.risky(), byTime.payload().risky());

        // 不指定版本 + 新时点：当前最新生效规则不能算错（t=850 应用 v3，decay=0.4）
        QueryOutcome<RiskConclusion> latest = propagation.evaluateOutcome("Z", 850L, null, null);
        assertTrue(latest.isOk());
        assertEquals(3, latest.payload().ruleVersion());
        assertEquals(0.16, latest.payload().riskScore(), 1e-9);
        assertFalse(latest.payload().risky(), "v3 下 0.16 < 0.5");
        log.info("不指定版本: Z@350 仍按 v1(score={}), Z@850 按 v3(score={}) —— 时间点语义正确",
                byTime.payload().riskScore(), latest.payload().riskScore());
    }

    @Test
    void compareTwoVersions_listsFlipsAndScoreDeltas() {
        rules.publish(2, 0.5, 0.2, 500L);  // v2: 阈值 0.5 -> 0.2
        rules.publish(2, 0.4, 0.5, 800L);  // v3: 衰减 0.5 -> 0.4

        // v1 vs v2 @ t=350：分值不变，Z 因阈值降低从正常翻为高风险
        QueryOutcome<RuleDiffResult> diff12 = replayService.compare(350L, 1, 2);
        assertTrue(diff12.isOk());
        RuleDiffResult d12 = diff12.payload();
        assertEquals(1, d12.becameRisky().size());
        assertEquals("Z", d12.becameRisky().get(0).address());
        assertEquals(0.25, d12.becameRisky().get(0).baseScore(), 1e-9);
        assertEquals(0.25, d12.becameRisky().get(0).targetScore(), 1e-9);
        assertEquals(0.0, d12.becameRisky().get(0).delta(), 1e-9);
        assertTrue(d12.cleared().isEmpty());
        assertEquals(1, d12.scoreChanges().size(), "仅 Z 判定翻转");
        log.info("对比 v1->v2 @350: 新增高风险={}, 解除={}, 分值变化={}",
                d12.becameRisky().size(), d12.cleared().size(), d12.scoreChanges().size());

        // v1 vs v3 @ t=350：衰减 0.5->0.4。Y 一跳 0.5->0.4 跌出阈值被解除；Z 两跳 0.25->0.16 仅分值变化
        QueryOutcome<RuleDiffResult> diff13 = replayService.compare(350L, 1, 3);
        assertTrue(diff13.isOk());
        RuleDiffResult d13 = diff13.payload();
        assertTrue(d13.becameRisky().isEmpty());
        assertEquals(1, d13.cleared().size());
        ScoreChange yCleared = d13.cleared().get(0);
        assertEquals("Y", yCleared.address());
        assertEquals(0.5, yCleared.baseScore(), 1e-9);
        assertEquals(0.4, yCleared.targetScore(), 1e-9);
        assertTrue(yCleared.baseRisky());
        assertFalse(yCleared.targetRisky());
        assertEquals(2, d13.scoreChanges().size(), "Y 翻转 + Z 分值变化");
        ScoreChange zChange = d13.scoreChanges().get(1);
        assertEquals("Z", zChange.address());
        assertEquals(0.25, zChange.baseScore(), 1e-9);
        assertEquals(0.16, zChange.targetScore(), 1e-9);
        assertEquals(-0.09, zChange.delta(), 1e-9);
        assertFalse(zChange.baseRisky());
        assertFalse(zChange.targetRisky());
        log.info("对比 v1->v3 @350: Y {} -> {} 解除高风险; Z {} -> {} (Δ{}) 判定不变",
                yCleared.baseScore(), yCleared.targetScore(),
                zChange.baseScore(), zChange.targetScore(), zChange.delta());

        // v2 vs v1 @ t=350（反向）：Z 从高风险解除为正常
        QueryOutcome<RuleDiffResult> diff21 = replayService.compare(350L, 2, 1);
        assertTrue(diff21.isOk());
        assertTrue(diff21.payload().becameRisky().isEmpty());
        assertEquals(1, diff21.payload().cleared().size());
        assertEquals("Z", diff21.payload().cleared().get(0).address());
        log.info("对比 v2->v1 @350: 解除高风险={}", diff21.payload().cleared().size());

        // 相同版本对比：无任何差异
        QueryOutcome<RuleDiffResult> same = replayService.compare(350L, 1, 1);
        assertTrue(same.isOk());
        assertTrue(same.payload().scoreChanges().isEmpty());

        // 不存在的版本：可解释拒绝
        QueryOutcome<RuleDiffResult> unknown = replayService.compare(350L, 1, 99);
        assertFalse(unknown.isOk());
        log.info("对比 v1->v99 被拒绝: reason={}, message={}",
                unknown.rejection().reason(), unknown.rejection().message());
    }
}
