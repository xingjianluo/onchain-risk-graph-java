package com.github.xingjianluo.onchainriskgraphjava.service;

import com.github.xingjianluo.onchainriskgraphjava.ledger.CommitClock;
import com.github.xingjianluo.onchainriskgraphjava.model.BatchReplayResult;
import com.github.xingjianluo.onchainriskgraphjava.model.RejectReason;
import com.github.xingjianluo.onchainriskgraphjava.model.ReplayPoint;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskConclusion;
import com.github.xingjianluo.onchainriskgraphjava.query.QueryInvalidException;
import com.github.xingjianluo.onchainriskgraphjava.query.QueryValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量历史回放。
 *
 * <p>语义：
 * <ul>
 *   <li><b>批次级一致性（commit fence）</b>：批次开始瞬间在全局提交水位钟上固定一个水位
 *       {@code fence}，整批所有点位只见 {@code seq <= fence} 的交易/风险标记/规则版本。
 *       回放进行期间新入账的交易、新登记的标记、新发布的规则——哪怕其
 *       {@code visibleAt/effectiveAt} 落在回放区间内——对整批一律不可见，
 *       每个点位的结论与“回放开始那一刻逐点单独查询”的结果逐字段一致；</li>
 *   <li>每个点位仍独立套用 {@code visibleAt/effectiveAt <= asOf} 的时间点过滤，
 *       时间戳确实晚于该时点的数据本来就算不进来，行为不变；</li>
 *   <li>点位按请求顺序逐个、串行评估，结果顺序与请求严格一致且可重复；</li>
 *   <li>不使用批量回放的普通单点查询不携带水位，语义与接口形态完全不变。</li>
 * </ul>
 */
@Component
public class BatchReplayService {

    private static final Logger log = LoggerFactory.getLogger(BatchReplayService.class);

    public static final int MAX_POINTS = 500;

    private final RiskPropagationService propagationService;
    private final CommitClock clock;

    /**
     * @param clock 必须与交易账本、风险标记注册表、规则服务共享的同一个提交水位钟
     *              （生产环境由 Spring 注入同一单例），否则批次水位无法对应存储中的提交序号
     */
    @Autowired
    public BatchReplayService(RiskPropagationService propagationService, CommitClock clock) {
        this.propagationService = propagationService;
        this.clock = clock;
    }

    /** 按给定点位顺序逐个回放，结果顺序与请求严格一致 */
    public BatchReplayResult replay(List<ReplayPoint> points) {
        validate(points);
        // 批次一致性关键：开始瞬间固定提交水位。此后任何新提交/新发布序号都大于 fence，
        // 对整批不可见——本批每个点位的结论在批次开始那一刻即已确定。
        long fence = clock.current();
        List<BatchReplayResult.PointResult> results = new ArrayList<>(points.size());
        log.info("========== 批量历史回放开始，点位 {} 个，提交水位 fence={} "
                + "(本批只见 seq<={} 的交易/标记/规则) ==========", points.size(), fence, fence);
        for (int i = 0; i < points.size(); i++) {
            ReplayPoint point = points.get(i);
            long asOf = QueryValidator.requireAsOf(point.asOf());

            beforePointEvaluation(i, point);

            boolean singleAddress = point.address() != null;
            // 显式给了 address（含空串/空白）必须合法；只有缺省(null)才表示全量
            List<RiskConclusion> conclusions;
            if (singleAddress) {
                String address = QueryValidator.requireAddress(point.address());
                conclusions = List.of(propagationService.evaluateFenced(address, asOf, fence));
            } else {
                conclusions = propagationService.evaluateAllFenced(asOf, fence);
            }
            results.add(new BatchReplayResult.PointResult(
                    new ReplayPoint(point.address(), asOf), conclusions));
            log.info("批量回放 [{}/{}] asOf={}{} (fence={}) => 结论 {} 条，首条 risky 数统计={}",
                    i + 1, points.size(), asOf,
                    singleAddress ? ", address=" + point.address().strip() : ", 全量",
                    fence, conclusions.size(),
                    conclusions.stream().filter(RiskConclusion::risky).count());
        }
        log.info("========== 批量历史回放结束，返回点位 {} 个，顺序与请求一致，"
                + "整批隔离于水位 fence={} 之后的并发写入 ==========", results.size(), fence);
        return new BatchReplayResult(points.size(), List.copyOf(results));
    }

    /**
     * 每个点位评估前的钩子（默认空实现）。
     * 测试可子类化本类，在回放进行中确定性地注入并发写入，复现
     * “写入时间戳落在回放区间内”的隔离场景。
     */
    protected void beforePointEvaluation(int index, ReplayPoint point) {
        // 默认无操作
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
