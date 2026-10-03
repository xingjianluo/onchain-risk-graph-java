package com.github.xingjianluo.onchainriskgraphjava.tests;

import com.github.xingjianluo.onchainriskgraphjava.ledger.TransactionLedger;
import com.github.xingjianluo.onchainriskgraphjava.model.RejectReason;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskConclusion;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskLabel;
import com.github.xingjianluo.onchainriskgraphjava.model.RuleVerdictChange;
import com.github.xingjianluo.onchainriskgraphjava.model.RuleVersionDiff;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitStatus;
import com.github.xingjianluo.onchainriskgraphjava.model.Transaction;
import com.github.xingjianluo.onchainriskgraphjava.query.QueryNotFoundException;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskLabelRegistry;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskPropagationService;
import com.github.xingjianluo.onchainriskgraphjava.service.RuleReplayService;
import com.github.xingjianluo.onchainriskgraphjava.service.RuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则版本回放与影响对比测试。
 *
 * <pre>
 *   A(风险源 base=1.0, t=0) --t1(at100)--> B --t2(at200)--> C
 * </pre>
 * v1: maxHops=2, decay=0.5, threshold=0.5, effectiveAt=0
 *     => A=1.0(risky) B=0.5(risky) C=0.25(safe)
 * v2: maxHops=3, decay=0.6, threshold=0.2, effectiveAt=500
 *     => A=1.0 B=0.6 C=0.36：B 分值变化(0.5->0.6)仍 risky，C 新升高风险(0.36>=0.2)
 * v3: maxHops=3, decay=0.6, threshold=0.9, effectiveAt=800
 *     => A=1.0(risky) B=0.6(safe) C=0.36(safe)：B、C 全部解除
 */
class RuleVersionReplayTest {

    private static final Logger log = LoggerFactory.getLogger(RuleVersionReplayTest.class);

    private TransactionLedger ledger;
    private RuleService rules;
    private RiskPropagationService propagation;
    private RuleReplayService replay;

    @BeforeEach
    void setUp() {
        ledger = new TransactionLedger();
        rules = new RuleService();
        RiskLabelRegistry labels = new RiskLabelRegistry();
        propagation = new RiskPropagationService(ledger, labels, rules);
        replay = new RuleReplayService(rules, propagation);

        assertEquals(SubmitStatus.ACCEPTED, rules.publish(2, 0.5, 0.5, 0L).status());   // v1
        labels.register(new RiskLabel("A", 1.0, 0L));
        ledger.submit(new Transaction("t1", "A", "B", 100, 100L));
        ledger.submit(new Transaction("t2", "B", "C", 60, 200L));
        assertEquals(SubmitStatus.ACCEPTED, rules.publish(3, 0.6, 0.2, 500L).status()); // v2
        assertEquals(SubmitStatus.ACCEPTED, rules.publish(3, 0.6, 0.9, 800L).status()); // v3
    }

    @Test
    void pinnedOldVersion_reproducesHistoricalConclusion_andLaterVersionsDoNotBiasIt() {
        long asOf = 350L;

        // 不显式指定：时间点语义 -> v1（350 < 500）
        RiskConclusion cDefault = propagation.evaluate("C", asOf);
        assertEquals(1, cDefault.ruleVersion());
        assertEquals(0.25, cDefault.riskScore(), 1e-9);
        assertFalse(cDefault.risky());

        // 显式指定 v1 回放：与当时历史结论一致
        RiskConclusion cV1 = replay.evaluateWithVersion("C", asOf, 1);
        assertEquals(1, cV1.ruleVersion());
        assertEquals(0.5, cV1.riskThreshold(), 1e-9);
        assertEquals(cDefault.riskScore(), cV1.riskScore(), 1e-12);
        assertFalse(cV1.risky());

        // 显式指定 v2（350 时 v2 尚未生效）：数据仍是 350 的快照，C 两跳 0.36 >= 0.2 => 翻盘
        RiskConclusion cV2 = replay.evaluateWithVersion("C", asOf, 2);
        assertEquals(2, cV2.ruleVersion());
        assertEquals(0.2, cV2.riskThreshold(), 1e-9);
        assertEquals(0.36, cV2.riskScore(), 1e-9, "分值只取决于数据快照，规则只改阈值/跳数/衰减");
        assertTrue(cV2.risky(), "0.36 在 v2 阈值 0.2 下翻盘为高风险");

        // 显式指定 v3（最新）回放同一历史时点：0.36 < 0.9，不高风险，不能被算成最新时点的结论
        RiskConclusion cV3 = replay.evaluateWithVersion("C", asOf, 3);
        assertEquals(3, cV3.ruleVersion());
        assertFalse(cV3.risky());

        // 旧版本回放重复执行，结论稳定
        assertEquals(cV1.riskScore(), replay.evaluateWithVersion("C", asOf, 1).riskScore(), 1e-12);

        log.info("========== 场景1：显式版本回放，历史结论不被后来规则带偏 ==========");
        log.info("C@350 默认(自动选v1): score={}, risky={}", cDefault.riskScore(), cDefault.risky());
        log.info("C@350 指定v1(decay=0.5,threshold=0.5): score={}, risky={} —— 与历史一致",
                cV1.riskScore(), cV1.risky());
        log.info("C@350 指定v2(decay=0.6,threshold=0.2): score={}, risky={} —— 同一批数据翻盘",
                cV2.riskScore(), cV2.risky());
        log.info("C@350 指定v3(decay=0.6,threshold=0.9): score={}, risky={} —— 严格按指定版本",
                cV3.riskScore(), cV3.risky());
        cV2.contributions().forEach(p -> log.info(
                "  v2 判定依据: 源={}, base={}, 跳数={}, 贡献=1.0*0.6^{}={}, 路径={}, tx={}",
                p.sourceAddress(), p.sourceBaseScore(), p.hops(), p.hops(), p.contribution(),
                String.join(" -> ", p.route()), String.join(" -> ", p.txIds())));

        // 当前最新规则下的新时点结论不被旧版本参数算错：t=900 自动选 v3
        RiskConclusion bAt900 = propagation.evaluate("B", 900L);
        assertEquals(3, bAt900.ruleVersion());
        assertEquals(0.6, bAt900.riskScore(), 1e-9);
        assertFalse(bAt900.risky(), "v3 阈值 0.9 下 B=0.6 不是高风险");
        log.info("B@900 默认语义自动选最新 v3: score={}, risky={}", bAt900.riskScore(), bAt900.risky());
    }

    @Test
    void diffTwoVersions_listsBecameRisky_becameSafe_andScoreChanges() {
        long asOf = 350L;

        // v1 -> v2：C 从正常变高风险（阈值 0.5 -> 0.2，decay 0.5 -> 0.6）
        RuleVersionDiff v1ToV2 = replay.compareVersions(asOf, 1, 2, null);
        assertEquals(1, v1ToV2.versionA());
        assertEquals(2, v1ToV2.versionB());
        assertEquals(0.5, v1ToV2.thresholdA(), 1e-9);
        assertEquals(0.2, v1ToV2.thresholdB(), 1e-9);
        List<String> becameRisky = v1ToV2.becameRisky().stream()
                .map(RuleVerdictChange::address).toList();
        assertEquals(List.of("C"), becameRisky);
        assertTrue(v1ToV2.becameSafe().isEmpty());
        RuleVerdictChange cChange = v1ToV2.becameRisky().get(0);
        assertEquals(0.25, cChange.scoreA(), 1e-9);
        assertEquals(0.36, cChange.scoreB(), 1e-9);
        assertEquals(0.11, cChange.scoreDelta(), 1e-9);

        // B 在两版下都 risky，但分值变化（0.5 -> 0.6），进入“仅分值变化”
        RuleVerdictChange bScoreChange = v1ToV2.scoreOnlyChanged().stream()
                .filter(c -> c.address().equals("B")).findFirst().orElseThrow();
        assertEquals(0.5, bScoreChange.scoreA(), 1e-9);
        assertEquals(0.6, bScoreChange.scoreB(), 1e-9);

        // v2 -> v3：B、C 全部解除高风险（阈值 0.2 -> 0.9）
        RuleVersionDiff v2ToV3 = replay.compareVersions(asOf, 2, 3, null);
        List<String> becameSafe = v2ToV3.becameSafe().stream()
                .map(RuleVerdictChange::address).toList();
        assertEquals(List.of("B", "C"), becameSafe);
        assertTrue(v2ToV3.becameRisky().isEmpty());
        // 仅阈值变化：分值不变
        v2ToV3.becameSafe().forEach(c ->
                assertEquals(0.0, c.scoreDelta(), 1e-9, "仅阈值变化不应改变分值"));

        // 两侧全量结论按地址字典序，且分值符合传播规则
        Map<String, RiskConclusion> byAddrV1 = v1ToV2.conclusionsA().stream()
                .collect(Collectors.toMap(RiskConclusion::address, Function.identity()));
        assertEquals(1.0, byAddrV1.get("A").riskScore(), 1e-9);
        assertEquals(0.5, byAddrV1.get("B").riskScore(), 1e-9);
        assertEquals(0.25, byAddrV1.get("C").riskScore(), 1e-9);
        assertEquals(List.of("A", "B", "C"),
                v1ToV2.conclusionsA().stream().map(RiskConclusion::address).toList());

        // 单地址对比：只比较 C（地址带空白也应被规范化）
        RuleVersionDiff single = replay.compareVersions(asOf, 1, 2, "  C  ");
        assertEquals(1, single.comparedAddresses());
        assertEquals(List.of("C"), single.becameRisky().stream()
                .map(RuleVerdictChange::address).toList());
        assertNotNull(single.becameRisky().get(0).conclusionA());
        assertNotNull(single.becameRisky().get(0).conclusionB());

        // 反向对比 v2 -> v1：原来的“新升”变成“解除”，对称可复现
        RuleVersionDiff reverse = replay.compareVersions(asOf, 2, 1, null);
        assertEquals(List.of("C"), reverse.becameSafe().stream()
                .map(RuleVerdictChange::address).toList());

        log.info("========== 场景2：v1->v2 差异（decay 0.5->0.6, threshold 0.5->0.2）==========");
        log.info("新升高风险={}, 解除={}, 仅分值变化地址={}, 比较地址数={}",
                becameRisky, List.of(),
                v1ToV2.scoreOnlyChanged().stream().map(RuleVerdictChange::address).toList(),
                v1ToV2.comparedAddresses());
        log.info("========== 场景3：v2->v3 差异（threshold 0.2->0.9）==========");
        log.info("新升高风险={}, 解除={}, 比较地址数={}",
                List.of(), becameSafe, v2ToV3.comparedAddresses());
        for (RuleVerdictChange ch : v2ToV3.becameSafe()) {
            log.info("  解除依据: {} score {} >= 阈值0.2 => risky; score {} < 阈值0.9 => safe",
                    ch.address(), ch.scoreA(), ch.scoreB());
        }
    }

    @Test
    void unknownVersion_isRejectedWithStableReason() {
        QueryNotFoundException ex = assertThrows(QueryNotFoundException.class,
                () -> replay.evaluateWithVersion("A", 350L, 99));
        assertEquals(RejectReason.UNKNOWN_RULE_VERSION, ex.reason());
        assertEquals(404, ex.reason().httpStatus());
        log.info("不存在版本被拒绝: code={}, message={}", ex.reason().code(), ex.getMessage());
    }
}
