package com.github.xingjianluo.onchainriskgraphjava.tests;

import com.github.xingjianluo.onchainriskgraphjava.domain.IngestResult;
import com.github.xingjianluo.onchainriskgraphjava.domain.IngestStatus;
import com.github.xingjianluo.onchainriskgraphjava.domain.RiskConclusion;
import com.github.xingjianluo.onchainriskgraphjava.domain.RiskLabel;
import com.github.xingjianluo.onchainriskgraphjava.domain.Transaction;
import com.github.xingjianluo.onchainriskgraphjava.exception.InvalidRuleException;
import com.github.xingjianluo.onchainriskgraphjava.ledger.InMemoryTransactionLedger;
import com.github.xingjianluo.onchainriskgraphjava.ledger.RiskLabelBook;
import com.github.xingjianluo.onchainriskgraphjava.propagation.RiskPropagator;
import com.github.xingjianluo.onchainriskgraphjava.rule.RiskRule;
import com.github.xingjianluo.onchainriskgraphjava.rule.RuleRegistry;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskGraphService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖：
 * 1. 快照查询只反映 asOf 之前可见交易，未来写入不影响历史结论（事件时间回放）；
 * 2. 规则阈值变更可复现历史结论（显式 ruleVersion / 时点自动选版）；
 * 3. 非法规则、同版本冲突规则不得静默生效。
 */
class SnapshotAndRuleTest {

    private static final Logger log = LoggerFactory.getLogger(SnapshotAndRuleTest.class);

    private RiskGraphService newService() {
        return new RiskGraphService(new InMemoryTransactionLedger(),
                new RiskLabelBook(), new RuleRegistry(), new RiskPropagator());
    }

    private Transaction tx(String id, String from, String to, long ts) {
        return new Transaction(id, from, to, new BigDecimal("10"), ts);
    }

    @Test
    void snapshotOnlyReflectsTransactionsVisibleAtPointInTime() {
        RiskGraphService svc = newService();
        svc.addLabel(new RiskLabel("S", 1.0d, "制裁名单", 100L));
        svc.ingest(tx("t1", "S", "A", 200L)); // 1 跳 => 0.5
        svc.ingest(tx("t2", "A", "B", 400L)); // 2 跳 => 0.25

        // 时点 300：t2 尚不可见，B 无风险；A 有 1 跳风险
        RiskConclusion bAt300 = svc.riskOf("B", 300L, null);
        RiskConclusion aAt300 = svc.riskOf("A", 300L, null);
        log.info("[快照回放] asOf=300 B.score={} A.score={}, 可见流水={}",
                bAt300.score(), aAt300.score(),
                svc.replay(300L).stream().map(Transaction::txId).toList());
        assertEquals(0.0d, bAt300.score(), 1e-9);
        assertEquals(0.5d, aAt300.score(), 1e-9);

        // 时点之后再写入 t3（500），并再次查 300：结论必须完全不变
        svc.ingest(tx("t3", "B", "C", 500L));
        RiskConclusion bAt300Again = svc.riskOf("B", 300L, null);
        assertEquals(bAt300.score(), bAt300Again.score(), 1e-9);
        assertEquals(0.0d, bAt300Again.score(), 1e-9);

        // 时点 600：B 出现 2 跳风险；流水回放只含 t1/t2/t3
        RiskConclusion bAt600 = svc.riskOf("B", 600L, null);
        assertEquals(0.25d, bAt600.score(), 1e-9);
        assertEquals(List.of("t1", "t2", "t3"),
                svc.replay(600L).stream().map(Transaction::txId).toList());
        log.info("[快照回放] asOf=600 B.score={} 可见流水=t1,t2,t3；历史 asOf=300 结论不变",
                bAt600.score());
    }

    @Test
    void ruleChangesReproduceHistoricalConclusions() {
        RiskGraphService svc = newService();
        svc.addLabel(new RiskLabel("S", 1.0d, "seed", 100L));
        svc.ingest(tx("t1", "S", "A", 200L));

        // 默认规则 default-v1：阈值 0.5，A 的 1 跳分 0.5 => risky
        RiskConclusion byDefault = svc.riskOf("A", 1000L, null);
        assertEquals("default-v1", byDefault.ruleVersion());
        assertTrue(byDefault.risky());

        // 新规则 10000 起生效：阈值提高到 0.8
        svc.registerRule(new RiskRule("strict-v1", 3, 0.5d, 0.8d, 10_000L, "更严格阈值"));

        // 在新时点用当时生效规则：同一份历史数据，0.5 < 0.8 => 不再 risky
        RiskConclusion byStrict = svc.riskOf("A", 20_000L, null);
        assertEquals("strict-v1", byStrict.ruleVersion());
        assertEquals(0.8d, byStrict.threshold(), 1e-9);
        assertFalse(byStrict.risky());

        // 显式指定旧版本规则：复现旧结论
        RiskConclusion reproduced = svc.riskOf("A", 20_000L, "default-v1");
        assertEquals("default-v1", reproduced.ruleVersion());
        assertEquals(byDefault.score(), reproduced.score(), 1e-9);
        assertTrue(reproduced.risky());

        // 规则版本幂等注册
        RiskRule r = new RiskRule("strict-v1", 3, 0.5d, 0.8d, 10_000L, "更严格阈值");
        assertEquals("strict-v1", svc.registerRule(r).version());
        log.info("[规则复现] 默认版 risky={}, 严格版 risky={}, 显式旧版复现 risky={}",
                byDefault.risky(), byStrict.risky(), reproduced.risky());
    }

    @Test
    void invalidAndConflictingRulesAreRejectedNotSilentlyApplied() {
        RuleRegistry registry = new RuleRegistry();

        // 非法取值
        assertThrows(InvalidRuleException.class,
                () -> registry.register(new RiskRule("bad-1", -1, 0.5d, 0.5d, 0L, "负跳数")));
        assertThrows(InvalidRuleException.class,
                () -> registry.register(new RiskRule("bad-2", 3, 1.5d, 0.5d, 0L, "衰减越界")));
        assertThrows(InvalidRuleException.class,
                () -> registry.register(new RiskRule("bad-3", 3, 0.5d, 2.0d, 0L, "阈值越界")));
        log.info("[规则校验] 非法规则全部被拒绝（maxHops/decay/threshold 越界）");

        // 同版本号内容冲突 => 拒绝静默覆盖
        registry.register(new RiskRule("v", 3, 0.5d, 0.5d, 0L, "原始"));
        InvalidRuleException ex = assertThrows(InvalidRuleException.class,
                () -> registry.register(new RiskRule("v", 5, 0.7d, 0.6d, 0L, "偷偷改写")));
        assertEquals("INVALID_RULE", ex.getCode());

        // 原规则保持不变，未被静默替换
        RiskRule still = registry.ruleByVersion("v");
        assertEquals(3, still.maxHops());
        assertEquals(0.5d, still.threshold(), 1e-9);
        log.info("[规则校验] 同版本冲突被拒绝，原规则保持不变: {}", still);
    }

    @Test
    void duplicateIngestUnderSnapshotStillCountedOnce() {
        RiskGraphService svc = newService();
        IngestResult r1 = svc.ingest(tx("dup", "S", "A", 200L));
        IngestResult r2 = svc.ingest(tx("dup", "S", "A", 200L));
        assertEquals(IngestStatus.ACCEPTED, r1.status());
        assertEquals(IngestStatus.DUPLICATE, r2.status());
        assertEquals(1, svc.replay(10_000L).size());
    }
}
