package com.github.xingjianluo.onchainriskgraphjava.tests;

import com.github.xingjianluo.onchainriskgraphjava.ledger.TransactionLedger;
import com.github.xingjianluo.onchainriskgraphjava.model.BatchReplayResult;
import com.github.xingjianluo.onchainriskgraphjava.model.ReplayPoint;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskConclusion;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskDataSnapshot;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskLabel;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskPath;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量回放“整批一致性 / 快照隔离”自动化测试。
 *
 * <pre>
 *   A(风险源 base=1.0, t=0) --t1(visibleAt=100)--> B --t2(visibleAt=200)--> C
 * </pre>
 * v1: maxHops=3, decay=0.5, threshold=0.5, effectiveAt=0
 *   => A=1.0(risky), B=0.5(risky), C=0.25(safe)
 *
 * <p>覆盖三类边界：
 * <ol>
 *   <li><b>主场景</b>：多轮（多线程）批量回放并发进行，期间注入三类“自称时间戳落在回放区间内”
 *       的数据——并发入账交易（visibleAt=100）、并发登记风险地址（C, effectiveAt=100）、
 *       并发发布规则版本（v2, effectiveAt=100）。断言每一轮、每个点位的结论都与回放开始前
 *       逐点单独查询的结果<b>逐字段（含每条传播路径）一致</b>，点位乱序、重复也一致，
 *       返回顺序仍随请求；</li>
 *   <li><b>对照1</b>：完全没有并发写入时，批量回放逐字段等于逐点单点查询；</li>
 *   <li><b>对照2</b>：回放期间写入的时间戳<b>确实更晚</b>（晚于所有点位）的数据，
 *       不进入本批历史点位；批次结束后的普通（非批量）单点查询按原时间点语义可见。</li>
 * </ol>
 *
 * <p><b>判定依据可稳定复现</b>：{@link GatedPropagation} 在每轮回放“快照已采集、首个点位
 * 尚未评估”处设置闸口，主场景先等所有轮次都采完快照（即回放确已开始），再在<b>回放进行中</b>
 * 注入数据，随后放行。这样旧实现（逐点位读实时状态）必然读到区间内注入而改写历史点位结论，
 * 断言必挂；快照隔离实现则整批冻结、逐字段不变。
 */
class BatchReplaySnapshotIsolationTest {

    private static final Logger log = LoggerFactory.getLogger(BatchReplaySnapshotIsolationTest.class);

    private TransactionLedger ledger;
    private RuleService rules;
    private RiskLabelRegistry labels;
    private GatedPropagation propagation;
    private BatchReplayService batch;

    /** 5 个去重点位（含单地址与全量），下面会打乱顺序并重复使用 */
    private final List<ReplayPoint> uniquePoints = List.of(
            new ReplayPoint(null, 50L),    // 全量：连 t1 都不可见
            new ReplayPoint("C", 150L),   // 单地址：t1 可见、t2 不可见 => C=0
            new ReplayPoint("B", 150L),   // 单地址：B=0.5
            new ReplayPoint("C", 250L),   // 单地址：全部可见 => C=0.25(safe)
            new ReplayPoint(null, 250L)   // 全量：A,B,C
    );

    @BeforeEach
    void setUp() {
        ledger = new TransactionLedger();
        rules = new RuleService();
        labels = new RiskLabelRegistry();
        propagation = new GatedPropagation(ledger, labels, rules);
        batch = new BatchReplayService(propagation);

        assertEquals(SubmitStatus.ACCEPTED, rules.publish(3, 0.5, 0.5, 0L).status());
        labels.register(new RiskLabel("A", 1.0, 0L));
        ledger.submit(new Transaction("t1", "A", "B", 100, 100L));
        ledger.submit(new Transaction("t2", "B", "C", 60, 200L));
    }

    /**
     * 主场景：多轮回放并发进行 + 区间内三类并发写入 => 整批结论固定为回放开始前的逐点结果。
     */
    @Test
    void concurrentInRangeWrites_duringMultipleReplays_neverEnterBatchResults() throws Exception {
        // 点位乱序 + 重复：顺序只跟请求走，同一点位重复多遍结论也必须一致
        List<ReplayPoint> arranged = new ArrayList<>();
        arranged.add(uniquePoints.get(4)); // 全量@250
        arranged.add(uniquePoints.get(1)); // C@150
        arranged.add(uniquePoints.get(0)); // 全量@50
        arranged.add(uniquePoints.get(4)); // 全量@250 重复
        arranged.add(uniquePoints.get(3)); // C@250
        arranged.add(uniquePoints.get(2)); // B@150
        arranged.add(uniquePoints.get(3)); // C@250 重复
        arranged.add(uniquePoints.get(1)); // C@150 重复
        arranged.add(uniquePoints.get(0)); // 全量@50 重复
        List<ReplayPoint> arrangedPoints = List.copyOf(arranged);

        // 1) 回放开始前，逐点单独查询，作为整批的期望基线（此刻没有任何区间内注入）
        List<List<RiskConclusion>> baselineByIndex = new ArrayList<>();
        for (ReplayPoint p : arrangedPoints) {
            baselineByIndex.add(singlePointQuery(p));
        }
        log.info("========== 主场景：回放开始前逐点基线已固定（{} 个点位）==========",
                arrangedPoints.size());

        int rounds = 4;
        propagation.resetGate(rounds);

        // 2) 后台同时持续写入“更晚时间戳”的噪声交易/标记（时间戳晚于全部点位，本来就不该进来）
        ExecutorService noisePool = Executors.newFixedThreadPool(2);
        AtomicBoolean noiseStop = new AtomicBoolean(false);
        CountDownLatch noiseStarted = new CountDownLatch(1);
        noisePool.submit(() -> runUntilStopped(noiseStop, noiseStarted,
                () -> {
                    int i = noiseSeq.getAndIncrement();
                    ledger.submit(new Transaction("late-tx-" + i, "C", "LN" + (i % 4),
                            7, 100_000L + i));
                }));
        noisePool.submit(() -> runUntilStopped(noiseStop, noiseStarted,
                () -> {
                    int i = noiseSeq.getAndIncrement();
                    labels.register(new RiskLabel("LL" + i, 0.6, 100_000L + i));
                }));
        assertTrue(noiseStarted.await(5, TimeUnit.SECONDS));

        // 3) 多轮批量回放并发开跑，每轮在“快照采集后、首点位评估前”停在闸口
        ExecutorService replayPool = Executors.newFixedThreadPool(rounds);
        List<BatchReplayResult> roundResults = new ArrayList<>();
        List<Throwable> roundErrors = new ArrayList<>();
        for (int r = 0; r < rounds; r++) {
            final int roundNo = r;
            replayPool.submit(() -> {
                try {
                    BatchReplayResult res = batch.replay(arrangedPoints);
                    synchronized (roundResults) {
                        roundResults.add(res);
                    }
                    log.info("第 {} 轮回放完成", roundNo + 1);
                } catch (Throwable t) {
                    synchronized (roundErrors) {
                        roundErrors.add(t);
                    }
                }
            });
        }

        // 等所有轮次都采完快照：此刻每轮回放都已开始、且都还没评估任何点位
        assertTrue(propagation.awaitSnapshotsCaptured(10, TimeUnit.SECONDS),
                "所有回放轮次都应在时限内完成快照采集并到达闸口");

        // 4) 关键：在“回放进行中”注入三类自称时间戳落在回放区间内的数据
        //    规则 v2：effectiveAt=100，会顶替 @150/@250 时点原本选用的 v1
        assertEquals(SubmitStatus.ACCEPTED,
                rules.publish(3, 0.5, 0.2, 100L).status(),
                "v2(effectiveAt=100) 发布成功，旧实现下会顶替历史点位的 v1");
        //    风险地址 C：effectiveAt=100，@150/@250 下 C 变成风险源自身(score=1)
        assertEquals(SubmitStatus.ACCEPTED,
                labels.register(new RiskLabel("C", 1.0, 100L)).status(),
                "风险地址 C(effectiveAt=100) 登记成功，旧实现下会改写 C 的历史结论");
        //    交易 g1：visibleAt=100，A->D 一跳，@150/@250 的全量结果多出 D
        assertEquals(SubmitStatus.ACCEPTED,
                ledger.submit(new Transaction("g1", "A", "D", 50, 100L)).status(),
                "区间内交易 g1(visibleAt=100) 入账成功，旧实现下会让全量结果多出 D");
        log.info("========== 回放进行中已注入区间内数据：规则v2@100、风险地址C@100、交易g1(visibleAt=100) ==========");

        // 5) 放行所有轮次继续评估
        propagation.openGate();
        replayPool.shutdown();
        assertTrue(replayPool.awaitTermination(15, TimeUnit.SECONDS), "所有回放轮次应在时限内结束");
        noiseStop.set(true);
        noisePool.shutdown();
        assertTrue(noisePool.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(roundErrors.isEmpty(),
                "回放线程不应抛异常: " + roundErrors);

        // 6) 每一轮、每个点位都与回放开始前的逐点基线逐字段一致，返回条数/顺序随请求
        assertEquals(rounds, roundResults.size());
        for (int r = 0; r < roundResults.size(); r++) {
            BatchReplayResult rr = roundResults.get(r);
            assertEquals(arrangedPoints.size(), rr.requested());
            assertEquals(arrangedPoints.size(), rr.points().size());
            for (int i = 0; i < arrangedPoints.size(); i++) {
                // 返回点位回显与顺序严格跟随请求
                assertEquals(arrangedPoints.get(i).asOf(), rr.points().get(i).point().asOf());
                assertEquals(arrangedPoints.get(i).address(), rr.points().get(i).point().address());
                assertConclusionsDeepEqual(baselineByIndex.get(i),
                        rr.points().get(i).conclusions(),
                        "第" + (r + 1) + "轮 点位#" + (i + 1)
                                + " asOf=" + arrangedPoints.get(i).asOf()
                                + " address=" + arrangedPoints.get(i).address());
            }
        }

        // 7) 关键复核：基线确实是“未注入”的样子（证明如果注入进来就一定会被逐字段比对抓到）
        RiskConclusion c250Baseline = baselineByIndex.get(indexOf(arrangedPoints,
                new ReplayPoint("C", 250L))).get(0);
        assertEquals(1, c250Baseline.ruleVersion(), "基线 C@250 应选 v1");
        assertEquals(0.25, c250Baseline.riskScore(), 1e-12, "基线 C@250 应为两跳 0.25(safe)");
        List<RiskConclusion> all250Baseline = baselineByIndex.get(indexOf(arrangedPoints,
                new ReplayPoint(null, 250L)));
        assertEquals(List.of("A", "B", "C"),
                all250Baseline.stream().map(RiskConclusion::address).toList(),
                "基线全量@250 只含 A,B,C，不含注入产生的 D");

        // 8) 隔离只在批量内部：批次结束后的普通单点查询仍是原时间点语义，能看到已提交数据
        RiskConclusion c250Live = propagation.evaluate("C", 250L);
        assertEquals(2, c250Live.ruleVersion(), "普通单点查询按实时状态自动选到 v2");
        assertEquals(1.0, c250Live.riskScore(), 1e-12, "C 已在实时状态中成为风险源自身，score=1");
        assertTrue(c250Live.risky());
        List<RiskConclusion> all250Live = propagation.evaluateAll(250L);
        assertEquals(List.of("A", "B", "C", "D"),
                all250Live.stream().map(RiskConclusion::address).toList(),
                "普通全量单点查询可见注入交易 g1 带来的新节点 D");
        // 批次结束后另起的一批（重新采集快照）也反映实时状态——说明不是全局冻结
        BatchReplayResult freshBatch = batch.replay(List.of(new ReplayPoint(null, 250L)));
        assertConclusionsDeepEqual(all250Live, freshBatch.points().get(0).conclusions(),
                "新一批次应读到批次之后的实时状态");

        log.info("========== 主场景判定依据 ==========");
        log.info("回放中注入：规则 v2(threshold=0.2,effectiveAt=100)、风险地址 C(effectiveAt=100)、"
                + "交易 g1 A->D(visibleAt=100)；多轮({})批量回放每个点位仍等于回放前基线", rounds);
        log.info("基线 C@250: ruleVersion=v1, score=0.25, safe；隔离批次内始终不变");
        log.info("批次后普通单点查询 C@250: ruleVersion=v2, score=1.0, risky；全量节点含 D => 隔离仅限本批");
    }

    /**
     * 对照1：没有任何并发写入时，批量回放（乱序+重复点位）逐字段等于逐点单点查询。
     */
    @Test
    void noConcurrentWrites_batchMatchesSinglePointQueries_fieldByField() {
        List<ReplayPoint> arranged = List.of(
                uniquePoints.get(3), // C@250
                uniquePoints.get(0), // 全量@50
                uniquePoints.get(2), // B@150
                uniquePoints.get(3), // C@250 重复
                uniquePoints.get(4), // 全量@250
                uniquePoints.get(1)  // C@150
        );
        BatchReplayResult result = batch.replay(arranged);
        assertEquals(arranged.size(), result.points().size());
        for (int i = 0; i < arranged.size(); i++) {
            assertEquals(arranged.get(i).asOf(), result.points().get(i).point().asOf());
            assertEquals(arranged.get(i).address(), result.points().get(i).point().address());
            assertConclusionsDeepEqual(singlePointQuery(arranged.get(i)),
                    result.points().get(i).conclusions(),
                    "无并发写入对照 点位 asOf=" + arranged.get(i).asOf());
        }
        log.info("========== 对照1：无并发写入，批量回放 {} 个点位（含重复）逐字段等于逐点单点查询 ==========",
                arranged.size());
    }

    /**
     * 对照2：回放期间写入的时间戳确实更晚（晚于全部点位）的数据：
     * 不进入本批任何历史点位；批次结束后的普通单点查询按原语义在其时间点之后可见。
     */
    @Test
    void genuinelyLaterWrites_excludedFromPastPoints_butVisibleToNormalQueriesAfter() throws Exception {
        List<ReplayPoint> points = List.of(
                uniquePoints.get(4), // 全量@250
                uniquePoints.get(3), // C@250
                uniquePoints.get(0)  // 全量@50
        );
        List<List<RiskConclusion>> baseline = points.stream().map(this::singlePointQuery).toList();

        propagation.resetGate(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        List<BatchReplayResult> holder = new ArrayList<>();
        pool.submit(() -> holder.add(batch.replay(points)));
        assertTrue(propagation.awaitSnapshotsCaptured(10, TimeUnit.SECONDS));

        // 回放进行中写入“时间戳确实更晚”的数据：晚于所有点位 asOf（最大 250）
        ledger.submit(new Transaction("late1", "C", "Z", 80, 900L));
        labels.register(new RiskLabel("Z", 1.0, 950L));
        // 不发布区间内规则；v1 对所有点位都适用
        log.info("========== 对照2：回放中写入更晚数据 late1(visibleAt=900)、风险Z(effectiveAt=950) ==========");

        propagation.openGate();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        BatchReplayResult rr = holder.get(0);
        for (int i = 0; i < points.size(); i++) {
            assertConclusionsDeepEqual(baseline.get(i), rr.points().get(i).conclusions(),
                    "更晚写入对照 点位 asOf=" + points.get(i).asOf());
        }

        // 时间戳确实晚于该时点的数据，过去/现在都不会算进该时点（普通单点查询也一样）
        assertConclusionsDeepEqual(baseline.get(1), List.of(propagation.evaluate("C", 250L)),
                "C@250 普通查询仍不应含 visibleAt=900 的 late1");
        assertConclusionsDeepEqual(baseline.get(0), propagation.evaluateAll(250L),
                "全量@250 普通查询仍不含 Z（late1/标记 Z 都晚于 250）");

        // 但到了其时间点之后，普通单点查询按原时间点语义可见
        RiskConclusion zAt1000 = propagation.evaluate("Z", 1000L);
        assertEquals(1.0, zAt1000.riskScore(), 1e-12, "Z 自身为风险源，score=1");
        assertTrue(zAt1000.risky());
        List<RiskConclusion> allAt1000 = propagation.evaluateAll(1000L);
        assertNotNull(allAt1000.stream().filter(c -> c.address().equals("Z")).findFirst().orElse(null),
                "t=1000 的全量普通查询应能看到 Z");

        log.info("========== 对照2判定依据：late1/Z 时间戳(900/950)晚于全部点位(<=250)，"
                + "本批历史点位与普通 @250 查询都不含；t=1000 的普通查询可见（原时间点语义不变）==========");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private final AtomicInteger noiseSeq = new AtomicInteger();

    private List<RiskConclusion> singlePointQuery(ReplayPoint p) {
        if (p.address() == null) {
            return propagation.evaluateAll(p.asOf());
        }
        return List.of(propagation.evaluate(p.address(), p.asOf()));
    }

    private static int indexOf(List<ReplayPoint> points, ReplayPoint target) {
        for (int i = 0; i < points.size(); i++) {
            ReplayPoint p = points.get(i);
            boolean sameAsOf = p.asOf().equals(target.asOf());
            boolean sameAddr = (p.address() == null)
                    ? target.address() == null
                    : p.address().equals(target.address());
            if (sameAsOf && sameAddr) {
                return i;
            }
        }
        throw new AssertionError("未找到点位 " + target);
    }

    private static void runUntilStopped(AtomicBoolean stop, CountDownLatch started, Runnable task) {
        started.countDown();
        while (!stop.get()) {
            task.run();
        }
    }

    /** 逐字段深比：地址/asOf/分值/risky/规则版本/阈值，以及每条传播路径（含路由与交易序列） */
    private static void assertConclusionsDeepEqual(List<RiskConclusion> expected,
                                                   List<RiskConclusion> actual, String msg) {
        assertEquals(expected.size(), actual.size(), msg + "（结论条数）");
        for (int i = 0; i < expected.size(); i++) {
            RiskConclusion e = expected.get(i);
            RiskConclusion a = actual.get(i);
            assertEquals(e.address(), a.address(), msg);
            assertEquals(e.asOf(), a.asOf(), msg);
            assertEquals(e.riskScore(), a.riskScore(), 1e-12, msg);
            assertEquals(e.risky(), a.risky(), msg);
            assertEquals(e.ruleVersion(), a.ruleVersion(), msg);
            assertEquals(e.riskThreshold(), a.riskThreshold(), 1e-12, msg);

            List<RiskPath> ep = e.contributions();
            List<RiskPath> ap = a.contributions();
            assertEquals(ep.size(), ap.size(), msg + "（贡献路径条数）");
            for (int j = 0; j < ep.size(); j++) {
                RiskPath x = ep.get(j);
                RiskPath y = ap.get(j);
                assertEquals(x.sourceAddress(), y.sourceAddress(), msg);
                assertEquals(x.sourceBaseScore(), y.sourceBaseScore(), 1e-12, msg);
                assertEquals(x.hops(), y.hops(), msg);
                assertEquals(x.contribution(), y.contribution(), 1e-12, msg);
                assertEquals(x.route(), y.route(), msg);
                assertEquals(x.txIds(), y.txIds(), msg);
            }
        }
    }

    /**
     * 在 {@link RiskPropagationService#captureSnapshot()} 上加闸口：
     * 快照采集后阻塞，直到测试放行，保证“注入发生在回放开始之后、点位评估之前”。
     */
    static final class GatedPropagation extends RiskPropagationService {
        private volatile CountDownLatch captured;
        private volatile CountDownLatch gate;

        GatedPropagation(TransactionLedger ledger, RiskLabelRegistry labels, RuleService rules) {
            super(ledger, labels, rules);
        }

        void resetGate(int rounds) {
            this.captured = new CountDownLatch(rounds);
            this.gate = new CountDownLatch(1);
        }

        boolean awaitSnapshotsCaptured(long timeout, TimeUnit unit) throws InterruptedException {
            return captured.await(timeout, unit);
        }

        void openGate() {
            gate.countDown();
        }

        @Override
        public RiskDataSnapshot captureSnapshot() {
            RiskDataSnapshot snapshot = super.captureSnapshot();
            CountDownLatch c = this.captured;
            CountDownLatch g = this.gate;
            if (c == null || g == null) {
                // 未武装闸口（对照用例）：行为与普通实现完全一致
                return snapshot;
            }
            c.countDown();
            try {
                g.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return snapshot;
        }
    }
}
