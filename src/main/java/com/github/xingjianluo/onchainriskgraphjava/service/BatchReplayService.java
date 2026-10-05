package com.github.xingjianluo.onchainriskgraphjava.service;

import com.github.xingjianluo.onchainriskgraphjava.model.BatchReplayResult;
import com.github.xingjianluo.onchainriskgraphjava.model.RejectReason;
import com.github.xingjianluo.onchainriskgraphjava.model.ReplayPoint;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskConclusion;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskDataSnapshot;
import com.github.xingjianluo.onchainriskgraphjava.query.QueryInvalidException;
import com.github.xingjianluo.onchainriskgraphjava.query.QueryValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量历史回放。
 *
 * <p>语义：
 * <ul>
 *   <li><b>整批一致性（快照隔离）</b>：一批回放开始时先采集一次全量数据快照（交易/风险标记/
 *       规则版本）。本批每个点位都只在这同一个快照上评估。回放进行期间并发入账的交易、
 *       并发登记的风险地址、并发发布的规则版本，即使自带的 visibleAt/effectiveAt 落在本批
 *       回放区间内（看上去“那个时点就已存在”），也一律不属于该快照，<b>不能进入这一批任何
 *       点位的结论，也不会顶替该时点原本应选用的规则版本</b>；点位串行、顺序与请求严格一致，
 *       同一点位重复跑、点位任意排列，结论都与回放开始那一刻逐点单独查询的结果逐字段一致；</li>
 *   <li>每个点位仍在快照内独立按 {@code visibleAt/effectiveAt <= asOf} 取可见子集，
 *       时间戳确实晚于该时点（回放开始前就已存在）的数据，过去不会算进来，现在也依然不会；</li>
 *   <li>每个点位的结论与“回放开始那一刻”对应的单点查询（单地址或全量）逐字段一致。</li>
 * </ul>
 *
 * <p>注意：快照隔离<b>只在一次批量回放内部</b>生效；不使用批量回放的普通单点查询
 * （{@code GET /api/risk} 等）仍保持原有时间点语义——每次查询读取当时的实时状态。
 */
@Component
public class BatchReplayService {

    private static final Logger log = LoggerFactory.getLogger(BatchReplayService.class);

    public static final int MAX_POINTS = 500;

    private final RiskPropagationService propagationService;

    public BatchReplayService(RiskPropagationService propagationService) {
        this.propagationService = propagationService;
    }

    /** 按给定点位顺序逐个回放，结果顺序与请求严格一致 */
    public BatchReplayResult replay(List<ReplayPoint> points) {
        validate(points);
        // 整批只采集一次快照：采集完成之后的一切并发写入都与本批结论无关，
        // 哪怕写入数据自称的时间戳在回放区间内。
        RiskDataSnapshot snapshot = propagationService.captureSnapshot();
        List<BatchReplayResult.PointResult> results = new ArrayList<>(points.size());
        log.info("========== 批量历史回放开始，点位 {} 个，快照 capturedAt={}（本批结论自此刻固定）==========",
                points.size(), snapshot.capturedAt());
        for (int i = 0; i < points.size(); i++) {
            ReplayPoint point = points.get(i);
            long asOf = QueryValidator.requireAsOf(point.asOf());

            boolean singleAddress = point.address() != null;
            // 显式给了 address（含空串/空白）必须合法；只有缺省(null)才表示全量
            List<RiskConclusion> conclusions;
            if (singleAddress) {
                String address = QueryValidator.requireAddress(point.address());
                conclusions = List.of(propagationService.evaluate(snapshot, address, asOf));
            } else {
                conclusions = propagationService.evaluateAll(snapshot, asOf);
            }
            results.add(new BatchReplayResult.PointResult(
                    new ReplayPoint(point.address(), asOf), conclusions));
            log.info("批量回放 [{}/{}] asOf={}{} => 结论 {} 条，首条 risky 数统计={}",
                    i + 1, points.size(), asOf,
                    singleAddress ? ", address=" + point.address().strip() : ", 全量",
                    conclusions.size(),
                    conclusions.stream().filter(RiskConclusion::risky).count());
        }
        log.info("========== 批量历史回放结束，返回点位 {} 个，顺序与请求一致；"
                + "全程基于同一快照，回放期间的并发写入未进入结论 ==========",
                results.size());
        return new BatchReplayResult(points.size(), List.copyOf(results));
    }

    private static void validate(List<ReplayPoint> points) {
        if (points == null || points.isEmpty()) {
            throw new QueryInvalidException(RejectReason.INVALID_BATCH,
                    "批量回放点位不能为空");
        }
        if (points.size() > MAX_POINTS) {
            throw new QueryInvalidException(RejectReason.INVALID_BATCH,
                    "批量回放点位数量上限 " + MAX_POINTS + "，实际 " + points.size());
        }
        for (int i = 0; i < points.size(); i++) {
            ReplayPoint p = points.get(i);
            if (p == null || p.asOf() == null) {
                throw new QueryInvalidException(RejectReason.INVALID_BATCH,
                        "第 " + (i + 1) + " 个点位缺少必填字段 asOf");
            }
            if (p.asOf() < 0) {
                throw new QueryInvalidException(RejectReason.INVALID_BATCH,
                        "第 " + (i + 1) + " 个点位 asOf 不能为负数，实际: " + p.asOf());
            }
        }
    }
}
