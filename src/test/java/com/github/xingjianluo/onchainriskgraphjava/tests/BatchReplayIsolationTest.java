package com.github.xingjianluo.onchainriskgraphjava.tests;

import com.github.xingjianluo.onchainriskgraphjava.ledger.CommitClock;
import com.github.xingjianluo.onchainriskgraphjava.ledger.TransactionLedger;
import com.github.xingjianluo.onchainriskgraphjava.model.BatchReplayResult;
import com.github.xingjianluo.onchainriskgraphjava.model.ReplayPoint;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskConclusion;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskLabel;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitStatus;
import com.github.xingjianluo.onchainriskgraphjava.model.Transaction;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量历史回放的批次级一致性（commit fence）测试。
 *
 * <p>覆盖业务：批量历史回放 vs 回放期间并发入账交易 / 并发登记风险地址 / 并发发布规则版本。
 * 核心判定依据：一批回放开始瞬间固定提交水位 fence，整批每个点位的结论都必须与
 * “回放开始那一刻逐点单独查询”的结果<b>逐字段</b>一致（record 全字段相等），
 * 与并发写入自带的 visibleAt/effectiveAt 是否落在回放区间内无关。
 *
 * <pre>
 *   A(风险源 base=1.0, eff=0) --t1(at100)--> B --t2(at200)--> C
 *   规则 v1: maxHops=3, decay=0.5, threshold=0.5, effectiveAt=0
 *   回放点位: (全量,50) (B,150) (C,250) (全量,250)
 * </pre>
 */
class BatchReplayIsolationTest {

    private static final Logger log = LoggerFactory.getLogger(BatchReplayIsolationTest.class);

    private static final List<ReplayPoint> POINTS = List.of(
            new ReplayPoint(null, 50L),
            new ReplayPoint("B", 150L),
            new ReplayPoint("C", 250L),
            new ReplayPoint(null, 250L));

    private CommitClock clock;
    private TransactionLedger ledger;
    private RuleService rules;
    private RiskLabelRegistry labels;
    private RiskPropagationService propagation;
    private BatchReplayService batch;

    @BeforeEach
    void setUp() {
        // 与生产 Spring 装配一致：三个存储与批量回放共享同一个提交水位钟
        clock = new CommitClock();
        ledger = new TransactionLedger(clock);
        rules = new RuleService(clock);
        labels = new RiskLabelRegistry(clock);
        propagation = new RiskPropagationService(ledger, labels, rules);
        batch = new BatchReplayService(propagation, clock);

        assertEquals(SubmitStatus.ACCEPTED, rules.publish(3, 0.5, 0.5, 0L).status());
        assertEquals(SubmitStatus.ACCEPTED, labels.register(new RiskLabel("A", 1.0, 0L)).status());
        assertEquals(SubmitStatus.ACCEPTED,
                ledger.submit(new Transaction("t1", "A", "B", 100, 100L)).status());
        assertEquals(SubmitStatus.ACCEPTED,
                ledger.submit(new Transaction("t2", "B", "C", 60, 200L)).status());
    }

    @Test
    void inRangeWritesDuringReplay_areExcludedFromWholeBatch() {
        log.info("========== [隔离场景1] 回放进行中写入“时间戳落在回放区间内”的"
                + "交易/风险地址/规则 => 整批结论不得改变 ==========");
        // 基线：回放开始那一刻逐点单独查询的结果（逐字段比较基准）
        List<List<RiskConclusion>> baseline = POINTS.stream().map(this::expectedAt).toList();

        // 在批次水位固定之后、第一个点位评估之前，确定性注入三笔“时间戳在过去”的并发写入
        List<SubmitStatus> poisonStatus = new CopyOnWriteArrayList<>();
        BatchReplayService instrumented = instrumented(index -> {
            if (index == 0) {
                poisonStatus.add(ledger.submit(
                        new Transaction("poison-tx", "P", "C", 5, 150L)).status());
                poisonStatus.add(labels.register(new RiskLabel("P", 1.0, 100L)).status());
                poisonStatus.add(rules.publish(3, 0.5, 0.01, 120L).status());
                log.info("[场景1] 批次 fence 已固定后注入并发写入: 交易poison-tx(visibleAt=150), "
                        + "风险地址P(effectiveAt=100), 规则v2(effectiveAt=120, threshold=0.01)");
            }
        });

        BatchReplayResult result = instrumented.replay(POINTS);

        assertEquals(List.of(SubmitStatus.ACCEPTED, SubmitStatus.ACCEPTED, SubmitStatus.ACCEPTED),
                poisonStatus, "三笔并发写入必须真实落库，否则场景没有复现");
        assertPointwiseEquals("[场景1] 回放期间新写入不得进入本批", result, baseline);
        log.info("[场景1] 判定依据: 4 个点位结论与回放开始前逐点查询逐字段一致，"
                + "尽管三笔写入的时间戳都落在回放区间 [50,250] 内");

        // 对照组1：若无批次隔离（直接按时间点评估），这些写入足以改写结论——证明场景非空
        List<RiskConclusion> unfencedC250 = List.of(propagation.evaluate("C", 250L));
        assertNotEquals(baseline.get(2), unfencedC250,
                "对照：若无批次隔离，并发写入会改写 C@250 的结论（场景有效）");
        RiskConclusion c250 = unfencedC250.get(0);
        log.info("[场景1] 对照: 批次结束后单点查询 C@250 => score={}, risky={}, 规则=v{}"
                + "（并发写入对批次之后的普通查询正常可见，隔离是批次级的）",
                c250.riskScore(), c250.risky(), c250.ruleVersion());
        assertTrue(c250.risky() && c250.ruleVersion() == 2,
                "批次结束后的普通单点查询应能看到新数据与新规则");
    }

    @Test
    void noConcurrentWrites_batchMatchesSinglePointQueries() {
        log.info("========== [对照边界1] 无并发写入：批量回放与逐点单查询完全一致 ==========");
        List<List<RiskConclusion>> baseline = POINTS.stream().map(this::expectedAt).toList();

        BatchReplayResult first = batch.replay(POINTS);
        BatchReplayResult second = batch.replay(POINTS);

        assertPointwiseEquals("[边界1] 无并发写入时批量回放须等于逐点单查询", first, baseline);
        assertPointwiseEquals("[边界1] 无并发写入时重复回放须稳定", second, baseline);
        log.info("[边界1] 判定依据: 两轮回放、4 个点位均与单点查询逐字段一致");
    }

    @Test
    void laterTimestampWritesDuringReplay_stillExcludedByAsOf() {
        log.info("========== [对照边界2] 回放进行中写入“时间戳确实更晚”的数据 => "
                + "以前不算进来，现在依然不算进来 ==========");
        List<List<RiskConclusion>> baseline = POINTS.stream().map(this::expectedAt).toList();

        BatchReplayService instrumented = instrumented(index -> {
            if (index == 0) {
                ledger.submit(new Transaction("late-tx", "C", "Z", 7, 900L));
                labels.register(new RiskLabel("LATE", 0.9, 900L));
                rules.publish(3, 0.5, 0.01, 900L);
                log.info("[边界2] 回放期间注入更晚时间戳数据: late-tx@900, 标记LATE@900, 规则v2@900");
            }
        });

        BatchReplayResult result = instrumented.replay(POINTS);
        assertPointwiseEquals("[边界2] 更晚时间戳的写入对历史点位本就不可见", result, baseline);

        // 批次结束后：历史时点结论不变（asOf 过滤），更晚时点正常可见
        RiskConclusion c250 = propagation.evaluate("C", 250L);
        assertEquals(baseline.get(2), List.of(c250), "更晚数据不得影响 asOf=250 的历史结论");
        RiskConclusion z1000 = propagation.evaluate("Z", 1000L);
        assertTrue(z1000.risky(), "asOf=1000 时更晚数据正常参与评估");
        log.info("[边界2] 判定依据: asOf=250 结论不变(规则仍 v{})，asOf=1000 Z 可见且 risky={}",
                c250.ruleVersion(), z1000.risky());
    }

    @Test
    void repeatedRounds_eachRoundConsistentWithItsOwnStart() {
        log.info("========== [隔离场景2] 多轮重复回放：每轮与该轮开始那一刻的逐点查询一致 ==========");
        // 第 1 轮：无并发写入
        List<List<RiskConclusion>> baseline1 = POINTS.stream().map(this::expectedAt).toList();
        BatchReplayResult round1 = batch.replay(POINTS);
        assertPointwiseEquals("[场景2] 第1轮", round1, baseline1);

        // 两轮之间正常提交“时间戳在过去”的数据（对下一轮而言是回放开始前已存在的数据）
        ledger.submit(new Transaction("poison-tx", "P", "C", 5, 150L));
        labels.register(new RiskLabel("P", 1.0, 100L));
        rules.publish(3, 0.5, 0.01, 120L);
        log.info("[场景2] 两轮之间提交 poison-tx@150 / 标记P@100 / 规则v2@120");

        // 第 2 轮：这些数据在批次开始前已提交，必须正常进入本批（隔离不是永久冻结）
        List<List<RiskConclusion>> baseline2 = POINTS.stream().map(this::expectedAt).toList();
        BatchReplayResult round2 = batch.replay(POINTS);
        assertPointwiseEquals("[场景2] 第2轮须等于该轮开始时的逐点查询", round2, baseline2);
        assertNotEquals(round1.points().get(2).conclusions(),
                round2.points().get(2).conclusions(),
                "第2轮开始前已提交的数据应正常生效（C@250 结论改变）");

        // 第 3 轮：无新写入，与第 2 轮逐字段一致
        BatchReplayResult round3 = batch.replay(POINTS);
        assertPointwiseEquals("[场景2] 第3轮无新写入须与第2轮一致", round3, baseline2);
        log.info("[场景2] 判定依据: 每轮回放都以该轮开始时刻为界，轮间已提交数据正常生效，"
                + "轮内结论稳定");
    }

    @Test
    void concurrentWriterThreadsDuringReplay_batchStable() throws Exception {
        log.info("========== [隔离场景3] 多线程并发写入(时间戳在回放区间内)与长批次回放赛跑 ==========");
        // 100 个点位循环覆盖 4 个逻辑点位，拉长批次窗口让并发写入充分交错
        List<ReplayPoint> manyPoints = new ArrayList<>();
        List<List<RiskConclusion>> baseline = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            ReplayPoint p = POINTS.get(i % POINTS.size());
            manyPoints.add(p);
            baseline.add(expectedAt(p));
        }

        CountDownLatch writersStart = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger txSeq = new AtomicInteger();
        AtomicInteger labelSeq = new AtomicInteger();
        AtomicInteger ruleSeq = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        // 三类写入的 visibleAt/effectiveAt 全部落在回放区间 [50,250] 内
        pool.submit(() -> awaitThenRun(writersStart, stop, () ->
                ledger.submit(new Transaction("w-tx-" + txSeq.getAndIncrement(),
                        "A", "W" + (txSeq.get() % 8), 1, 150L))));
        pool.submit(() -> awaitThenRun(writersStart, stop, () ->
                labels.register(new RiskLabel("WL" + labelSeq.getAndIncrement(), 0.8, 100L))));
        pool.submit(() -> awaitThenRun(writersStart, stop, () ->
                rules.publish(3, 0.5, 0.01, 120L + ruleSeq.getAndIncrement())));

        // 第一个点位评估前（fence 已固定）放行写线程
        BatchReplayService instrumented = instrumented(index -> {
            if (index == 0) {
                writersStart.countDown();
            }
        });
        BatchReplayResult result = instrumented.replay(manyPoints);

        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertPointwiseEquals("[场景3] 并发写入赛跑期间整批结果不得改变", result, baseline);
        log.info("[场景3] 判定依据: 回放期间并发入账 {} 笔交易(visibleAt=150)、登记 {} 个风险地址"
                + "(effectiveAt=100)、发布到规则 v{}(effectiveAt>=120)，"
                + "100 个点位结论全部与回放开始前逐点查询逐字段一致",
                txSeq.get(), labelSeq.get(), rules.currentVersionNumber());
    }

    /** 单点查询语义下的期望结论（批量回放每一点都必须与之逐字段一致） */
    private List<RiskConclusion> expectedAt(ReplayPoint p) {
        return p.address() == null
                ? propagation.evaluateAll(p.asOf())
                : List.of(propagation.evaluate(p.address(), p.asOf()));
    }

    /** 构造一个在点位评估前执行钩子的批量回放服务（复现回放进行中的并发写入） */
    private BatchReplayService instrumented(IntConsumer hook) {
        return new BatchReplayService(propagation, clock) {
            @Override
            protected void beforePointEvaluation(int index, ReplayPoint point) {
                hook.accept(index);
            }
        };
    }

    private static void awaitThenRun(CountDownLatch start, AtomicBoolean stop, Runnable write) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        // 限制写入次数，既保证交错又控制批次耗时
        for (int i = 0; i < 400 && !stop.get(); i++) {
            write.run();
        }
    }

    /** 逐点位、逐字段（record 全字段相等）断言批量结果与期望一致 */
    private static void assertPointwiseEquals(String msg, BatchReplayResult result,
                                              List<List<RiskConclusion>> expected) {
        assertEquals(expected.size(), result.points().size(), msg + "（点位数）");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), result.points().get(i).conclusions(),
                    msg + "（点位 " + i + " asOf=" + result.points().get(i).point().asOf() + "）");
        }
    }
}
