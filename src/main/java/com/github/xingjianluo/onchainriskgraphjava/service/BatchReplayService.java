package com.github.xingjianluo.onchainriskgraphjava.service;

import com.github.xingjianluo.onchainriskgraphjava.model.BatchReplayResult;
import com.github.xingjianluo.onchainriskgraphjava.model.RejectReason;
import com.github.xingjianluo.onchainriskgraphjava.model.ReplayPoint;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskConclusion;
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
 *   <li>每个点位独立取 {@code visibleAt/effectiveAt <= asOf} 的快照，
 *       回放进行期间写入的新交易、发布的新规则，对“已经过去的时间点”不可见，结论不变；</li>
 *   <li>点位按请求顺序逐个、串行评估，结果顺序与请求严格一致且可重复；</li>
 *   <li>每个点位的结论与对应的单点查询（单地址或全量）逐字段一致。</li>
 * </ul>
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
        List<BatchReplayResult.PointResult> results = new ArrayList<>(points.size());
        log.info("========== 批量历史回放开始，点位 {} 个 ==========", points.size());
        for (int i = 0; i < points.size(); i++) {
            ReplayPoint point = points.get(i);
            long asOf = QueryValidator.requireAsOf(point.asOf());

            boolean singleAddress = point.address() != null;
            // 显式给了 address（含空串/空白）必须合法；只有缺省(null)才表示全量
            List<RiskConclusion> conclusions;
            if (singleAddress) {
                String address = QueryValidator.requireAddress(point.address());
                conclusions = List.of(propagationService.evaluate(address, asOf));
            } else {
                conclusions = propagationService.evaluateAll(asOf);
            }
            results.add(new BatchReplayResult.PointResult(
                    new ReplayPoint(point.address(), asOf), conclusions));
            log.info("批量回放 [{}/{}] asOf={}{} => 结论 {} 条，首条 risky 数统计={}",
                    i + 1, points.size(), asOf,
                    singleAddress ? ", address=" + point.address().strip() : ", 全量",
                    conclusions.size(),
                    conclusions.stream().filter(RiskConclusion::risky).count());
        }
        log.info("========== 批量历史回放结束，返回点位 {} 个，顺序与请求一致 ==========",
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
