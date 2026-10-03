package com.github.xingjianluo.onchainriskgraphjava.tests;

import com.github.xingjianluo.onchainriskgraphjava.ledger.TransactionLedger;
import com.github.xingjianluo.onchainriskgraphjava.model.QueryOutcome;
import com.github.xingjianluo.onchainriskgraphjava.model.QueryRejectReason;
import com.github.xingjianluo.onchainriskgraphjava.model.ReplayItem;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskConclusion;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskLabel;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitStatus;
import com.github.xingjianluo.onchainriskgraphjava.model.Transaction;
import com.github.xingjianluo.onchainriskgraphjava.service.ReplayScope;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskLabelRegistry;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskPropagationService;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskReplayService;
import com.github.xingjianluo.onchainriskgraphjava.service.RuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量历史回放测试。
 *
 * <pre>
 *   X(风险源 base=1.0, t=0) --t1(at100)--> Y --t2(at200)--> Z
 *   规则 v1: maxHops=2, decay=0.5, threshold=0.5, effectiveAt=0
 * </pre>
 * 回放时间点 [350, 150, 250]（故意乱序，验证返回顺序与请求一致）。
 */
class BatchReplayTest {

    private static final Logger log = LoggerFactory.getLogger(BatchReplayTest.class);

    private TransactionLedger ledger;
    private RuleService rules;
    private RiskLabelRegistry labels;
    private RiskPropagationService propagation;
    private RiskReplayService replayService;

    @BeforeEach
    void setUp() {
        ledger = new TransactionLedger();
        rules = new RuleService();
        labels = new RiskLabelRegistry();
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
    void batchMatchesIndividualQueries_andPreservesRequestOrder() {
        List<Long> asOfs = List.of(350L, 150L, 250L);

        // 逐点单查的期望结论
        List<RiskConclusion> expected = asOfs.stream()
                .map(t -> propagation.evaluate("Z", t))
                .toList();
        log.info("========== 逐点单查期望结论（Z） ==========");
        expected.forEach(c -> log.info("  Z@{}: score={}, risky={}, ruleVersion=v{}",
                c.asOf(), c.riskScore(), c.risky(), c.ruleVersion()));

        List<ReplayItem> batch = replayService.replay("Z", asOfs, null);

        assertEquals(asOfs.size(), batch.size());
        for (int i = 0; i < asOfs.size(); i++) {
            ReplayItem item = batch.get(i);
            assertEquals(asOfs.get(i), item.asOf(), "第 " + i + " 个结果必须对应请求顺序");
            assertTrue(item.outcome().isOk());
            assertEquals(expected.get(i), item.outcome().payload(),
                    "批量回放 t=" + asOfs.get(i) + " 的结论必须与逐点单查完全一致");
        }
        log.info("========== 批量回放结果（顺序与请求一致，结论逐位一致） ==========");
        batch.forEach(item -> log.info("  回放点 t={}: score={}, risky={}",
                item.asOf(), item.outcome().payload().riskScore(),
                item.outcome().payload().risky()));

        // 显式指定版本的批量回放：每一点都用指定版本
        List<ReplayItem> withVersion = replayService.replay("Z", asOfs, 1);
        for (int i = 0; i < asOfs.size(); i++) {
            assertEquals(expected.get(i), withVersion.get(i).outcome().payload(),
                    "显式 v1 的批量回放与历史结论一致");
        }
    }

    @Test
    void inflightWritesDoNotLeakIntoReplayScope() {
        // 回放开始：截取一致性边界
        ReplayScope scope = propagation.captureScope();
        RiskConclusion before = propagation.evaluateOutcome("Z", 350L, null, scope).payload();
        assertEquals(0.25, before.riskScore(), 1e-9);
        assertEquals(1, before.ruleVersion());

        // 回放进行期间：写入"过去时点"的交易、登记过去生效的标记、发布覆盖过去时点的新规则
        assertEquals(SubmitStatus.ACCEPTED,
                ledger.submit(new Transaction("t3", "X", "Z", 10, 100L)).status());
        assertEquals(SubmitStatus.ACCEPTED,
                labels.register(new RiskLabel("W", 0.9, 0L)).status());
        assertEquals(SubmitStatus.ACCEPTED, rules.publish(2, 0.5, 0.1, 200L).status());

        // 同一回放边界内：结论一动不动（t3/W/v2 都看不到）
        RiskConclusion scoped = propagation.evaluateOutcome("Z", 350L, null, scope).payload();
        assertEquals(before, scoped, "回放边界内的结论不受进行中的写入影响");
        log.info("回放边界内 Z@350: score={}, ruleVersion=v{} —— 与截取边界前一致",
                scoped.riskScore(), scoped.ruleVersion());

        // 边界外（普通单点查询）：这些写入确实已生效，证明隔离来自边界而非写入失败
        RiskConclusion unbounded = propagation.evaluateOutcome("Z", 350L, null, null).payload();
        assertEquals(2, unbounded.ruleVersion(), "边界外应看到新发布的 v2");
        assertTrue(unbounded.risky(), "边界外 v2 阈值 0.1 下应判高风险");
        assertTrue(unbounded.riskScore() > scoped.riskScore(),
                "边界外应看到 t3 与 W 带来的额外贡献");
        log.info("回放边界外 Z@350: score={}, ruleVersion=v{} —— 写入已生效，隔离确实来自回放边界",
                unbounded.riskScore(), unbounded.ruleVersion());

        // 边界内显式指定一个边界之后才发布的版本：拒绝，不能越界
        QueryOutcome<RiskConclusion> beyond = propagation.evaluateOutcome("Z", 350L, 2, scope);
        assertFalse(beyond.isOk());
        assertEquals(QueryRejectReason.UNKNOWN_RULE_VERSION, beyond.rejection().reason());
        log.info("边界内指定边界外版本被拒绝: reason={}, message={}",
                beyond.rejection().reason(), beyond.rejection().message());
    }

    @Test
    void batchReplayStableWhileWritesAndRulePublishesContinue() throws Exception {
        List<Long> asOfs = List.of(150L, 250L, 350L);
        // 期望：写入开始前的批量回放结果
        List<ReplayItem> expected = replayService.replay("Z", asOfs, null);
        expected.forEach(item -> assertTrue(item.outcome().isOk()));
        log.info("========== 期望结论（写入前批量回放） ==========");
        expected.forEach(item -> log.info("  Z@{}: score={}, risky={}",
                item.asOf(), item.outcome().payload().riskScore(),
                item.outcome().payload().risky()));

        int rounds = 50;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicBoolean writerFailed = new AtomicBoolean(false);

        // 写入线程：持续写入回放时点看不到的未来交易、发布未来生效的规则、登记未来生效的标记
        pool.submit(() -> {
            try {
                start.await();
                int i = 0;
                while (!stop.get()) {
                    long future = 900L + i;
                    ledger.submit(new Transaction("f" + i, "X", "Z", 5, future));
                    rules.publish(2, 0.5, 0.4, future);
                    labels.register(new RiskLabel("F" + i, 0.7, future));
                    i++;
                }
                log.info("写入线程结束，共写入 {} 轮未来数据", i);
            } catch (Exception e) {
                writerFailed.set(true);
                log.error("写入线程异常", e);
            }
        });

        // 回放线程：并发进行 50 批回放，每批都必须与期望完全一致
        pool.submit(() -> {
            try {
                start.await();
                for (int r = 0; r < rounds; r++) {
                    List<ReplayItem> batch = replayService.replay("Z", asOfs, null);
                    assertEquals(expected.size(), batch.size());
                    for (int i = 0; i < batch.size(); i++) {
                        assertEquals(expected.get(i).asOf(), batch.get(i).asOf());
                        assertEquals(expected.get(i).outcome().payload(),
                                batch.get(i).outcome().payload(),
                                "第 " + r + " 批 t=" + asOfs.get(i) + " 在并发写入下结论必须稳定");
                    }
                }
                log.info("并发回放线程完成 {} 批，全部与期望一致", rounds);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                stop.set(true);
            }
        });

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发回放应在时限内完成");
        assertFalse(writerFailed.get(), "写入线程不应异常");
    }

    @Test
    void singlePointRejectionDoesNotBreakTheBatch() {
        // 时间点列表中混入一个非法点（负数），其余点正常回放
        List<ReplayItem> batch = replayService.replay("Z", List.of(150L, -1L, 350L), null);
        assertEquals(3, batch.size());
        assertTrue(batch.get(0).outcome().isOk());
        assertFalse(batch.get(1).outcome().isOk());
        assertEquals(QueryRejectReason.INVALID_AS_OF, batch.get(1).outcome().rejection().reason());
        assertTrue(batch.get(2).outcome().isOk());
        assertEquals(propagation.evaluate("Z", 350L), batch.get(2).outcome().payload());
        log.info("批量回放中 t=-1 被拒绝(reason={})，其余点正常: t=150 score={}, t=350 score={}",
                batch.get(1).outcome().rejection().reason(),
                batch.get(0).outcome().payload().riskScore(),
                batch.get(2).outcome().payload().riskScore());
    }
}
