package com.github.xingjianluo.onchainriskgraphjava.tests;

import com.github.xingjianluo.onchainriskgraphjava.ledger.TransactionLedger;
import com.github.xingjianluo.onchainriskgraphjava.model.BatchReplayResult;
import com.github.xingjianluo.onchainriskgraphjava.model.RejectReason;
import com.github.xingjianluo.onchainriskgraphjava.model.ReplayPoint;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskConclusion;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskLabel;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitStatus;
import com.github.xingjianluo.onchainriskgraphjava.model.Transaction;
import com.github.xingjianluo.onchainriskgraphjava.query.QueryInvalidException;
import com.github.xingjianluo.onchainriskgraphjava.service.BatchReplayService;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskLabelRegistry;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskPropagationService;
import com.github.xingjianluo.onchainriskgraphjava.service.RuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量历史回放测试。
 *
 * <pre>
 *   A(风险源 base=1.0, t=0) --t1(at100)--> B --t2(at200)--> C
 * </pre>
 * v1: maxHops=3, decay=0.5, threshold=0.5, effectiveAt=0
 */
class BatchReplayTest {

    private static final Logger log = LoggerFactory.getLogger(BatchReplayTest.class);

    private TransactionLedger ledger;
    private RuleService rules;
    private RiskLabelRegistry labels;
    private RiskPropagationService propagation;
    private BatchReplayService batch;

    @BeforeEach
    void setUp() {
        // 三个存储与批量回放共享同一个提交水位钟，与生产 Spring 装配一致
        com.github.xingjianluo.onchainriskgraphjava.ledger.CommitClock clock =
                new com.github.xingjianluo.onchainriskgraphjava.ledger.CommitClock();
        ledger = new TransactionLedger(clock);
        rules = new RuleService(clock);
        labels = new RiskLabelRegistry(clock);
        propagation = new RiskPropagationService(ledger, labels, rules);
        batch = new BatchReplayService(propagation, clock);

        assertEquals(SubmitStatus.ACCEPTED, rules.publish(3, 0.5, 0.5, 0L).status());
        labels.register(new RiskLabel("A", 1.0, 0L));
        ledger.submit(new Transaction("t1", "A", "B", 100, 100L));
        ledger.submit(new Transaction("t2", "B", "C", 60, 200L));
    }

    @Test
    void batchResults_identicalToSinglePointQueries_andOrderStable() {
        List<ReplayPoint> points = List.of(
                new ReplayPoint(null, 50L),    // 全量
                new ReplayPoint("C", 150L),   // 单地址，t2 尚不可见
                new ReplayPoint("C", 250L),   // 单地址，全部可见
                new ReplayPoint(null, 250L),  // 全量
                new ReplayPoint("B", 250L)    // 单地址
        );

        BatchReplayResult result = batch.replay(points);
        assertEquals(5, result.requested());
        assertEquals(5, result.points().size());

        // 顺序与请求严格一致
        for (int i = 0; i < points.size(); i++) {
            assertEquals(points.get(i).asOf(), result.points().get(i).point().asOf());
        }

        // 逐点位与对应单点查询完全一致
        // 点位0：全量@50（无可见交易，节点只含风险源 A）
        assertConclusionsEqual(propagation.evaluateAll(50L),
                result.points().get(0).conclusions());
        // 点位1：C@150，score=0
        assertConclusionsEqual(List.of(propagation.evaluate("C", 150L)),
                result.points().get(1).conclusions());
        assertEquals(0.0, result.points().get(1).conclusions().get(0).riskScore(), 1e-9);
        // 点位2：C@250，两跳 0.25 未达阈值
        assertConclusionsEqual(List.of(propagation.evaluate("C", 250L)),
                result.points().get(2).conclusions());
        assertEquals(0.25, result.points().get(2).conclusions().get(0).riskScore(), 1e-9);
        // 点位3：全量@250 按地址字典序 A,B,C
        assertConclusionsEqual(propagation.evaluateAll(250L),
                result.points().get(3).conclusions());
        assertEquals(List.of("A", "B", "C"),
                result.points().get(3).conclusions().stream().map(RiskConclusion::address).toList());

        // 打乱顺序重放：每个点位结果仍一致（顺序只跟请求走）
        List<ReplayPoint> shuffled = List.of(points.get(3), points.get(1), points.get(0),
                points.get(4), points.get(2));
        BatchReplayResult shuffledResult = batch.replay(shuffled);
        for (int i = 0; i < shuffled.size(); i++) {
            ReplayPoint p = shuffled.get(i);
            List<RiskConclusion> expected = (p.address() == null)
                    ? propagation.evaluateAll(p.asOf())
                    : List.of(propagation.evaluate(p.address(), p.asOf()));
            assertConclusionsEqual(expected, shuffledResult.points().get(i).conclusions());
        }

        log.info("========== 批量回放与单点查询逐点位一致、顺序稳定 ==========");
        for (BatchReplayResult.PointResult pr : result.points()) {
            log.info("点位 asOf={}, address={} => {} 条结论, risky={}",
                    pr.point().asOf(), pr.point().address(), pr.conclusions().size(),
                    pr.conclusions().stream().filter(RiskConclusion::risky)
                            .map(RiskConclusion::address).toList());
        }
    }

    @Test
    void concurrentWritesAndRulePublishes_doNotChangePastReplayResults() throws Exception {
        // 先记录“过去时间点”的期望结论（此刻还没有任何晚于这些时点的写入）
        List<ReplayPoint> pastPoints = List.of(
                new ReplayPoint(null, 50L),
                new ReplayPoint("B", 150L),
                new ReplayPoint("C", 250L),
                new ReplayPoint(null, 250L));
        BatchReplayResult before = batch.replay(pastPoints);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        List<Runnable> writers = List.of(
                // 持续写入更晚可见的新交易
                () -> {
                    int i = 0;
                    while (!stop.get()) {
                        ledger.submit(new Transaction("late-" + i, "C", "D" + (i % 4),
                                10, 500L + i));
                        i++;
                    }
                },
                // 持续登记更晚生效的新风险源
                () -> {
                    int i = 0;
                    while (!stop.get()) {
                        labels.register(new RiskLabel("L" + (i % 3), 1.0, 600L + i));
                        i++;
                    }
                },
                // 持续发布新规则版本（effectiveAt 从 1000 起，不触碰历史时点的自动选择）
                () -> {
                    int i = 0;
                    while (!stop.get()) {
                        rules.publish(3, 0.5, 0.1 + (i % 5) * 0.1, 1000L + i);
                        i++;
                    }
                }
        );
        for (Runnable r : writers) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                r.run();
            });
        }
        start.countDown();

        // 并发期间反复批量回放历史点位，每一次都必须与写入前完全一致
        List<BatchReplayResult> during = new ArrayList<>();
        for (int round = 0; round < 20; round++) {
            during.add(batch.replay(pastPoints));
        }
        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        for (int round = 0; round < during.size(); round++) {
            BatchReplayResult r = during.get(round);
            assertEquals(before.requested(), r.requested());
            for (int i = 0; i < before.points().size(); i++) {
                assertConclusionsEqual(before.points().get(i).conclusions(),
                        r.points().get(i).conclusions(),
                        "第" + (round + 1) + "轮并发回放点位 asOf="
                                + pastPoints.get(i).asOf());
            }
        }

        log.info("========== 并发写入 {} 笔晚到交易期间，历史批量回放 20 轮全部不变 ==========",
                ledger.visibleCount(Long.MAX_VALUE));
        log.info("当前规则版本数={}（均自 t>=1000 生效，不影响 50/150/250 的历史结论）",
                rules.currentVersionNumber());
    }

    @Test
    void invalidBatch_isRejectedWithStableReason() {
        QueryInvalidException empty = assertThrows(QueryInvalidException.class,
                () -> batch.replay(List.of()));
        assertEquals(RejectReason.INVALID_BATCH, empty.reason());

        QueryInvalidException missingAsOf = assertThrows(QueryInvalidException.class,
                () -> batch.replay(List.of(new ReplayPoint("A", null))));
        assertEquals(RejectReason.INVALID_BATCH, missingAsOf.reason());

        QueryInvalidException tooMany = assertThrows(QueryInvalidException.class,
                () -> batch.replay(
                        java.util.Collections.nCopies(BatchReplayService.MAX_POINTS + 1,
                                new ReplayPoint("A", 1L))));
        assertEquals(RejectReason.INVALID_BATCH, tooMany.reason());
        log.info("批量非法拒绝彼此可区分: empty={}, missingAsOf={}, tooMany=INVALID_BATCH",
                empty.reason().code(), missingAsOf.reason().code());
    }

    private static void assertConclusionsEqual(List<RiskConclusion> expected,
                                               List<RiskConclusion> actual) {
        assertConclusionsEqual(expected, actual, "结论应逐字段一致");
    }

    private static void assertConclusionsEqual(List<RiskConclusion> expected,
                                               List<RiskConclusion> actual, String msg) {
        assertEquals(expected.size(), actual.size(), msg + "（条数）");
        for (int i = 0; i < expected.size(); i++) {
            RiskConclusion e = expected.get(i);
            RiskConclusion a = actual.get(i);
            assertEquals(e.address(), a.address(), msg);
            assertEquals(e.asOf(), a.asOf(), msg);
            assertEquals(e.riskScore(), a.riskScore(), 1e-12, msg);
            assertEquals(e.risky(), a.risky(), msg);
            assertEquals(e.ruleVersion(), a.ruleVersion(), msg);
            assertEquals(e.riskThreshold(), a.riskThreshold(), 1e-12, msg);
        }
    }
}
