package com.github.xingjianluo.onchainriskgraphjava.service;

import com.github.xingjianluo.onchainriskgraphjava.model.FundFlow;
import com.github.xingjianluo.onchainriskgraphjava.model.QueryOutcome;
import com.github.xingjianluo.onchainriskgraphjava.model.QueryRejectReason;
import com.github.xingjianluo.onchainriskgraphjava.model.QueryRejection;
import com.github.xingjianluo.onchainriskgraphjava.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 资金追踪：与风险传播共用同一快照图视图，保证“只看到 asOf（含）之前交易”的口径一致。
 */
@Component
public class FundFlowService {

    private static final Logger log = LoggerFactory.getLogger(FundFlowService.class);

    private final RiskPropagationService propagationService;

    public FundFlowService(RiskPropagationService propagationService) {
        this.propagationService = propagationService;
    }

    public FundFlow summarize(String address, long asOf) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("address 不能为空");
        }
        return doSummarize(address, asOf);
    }

    /** 可解释拒绝版资金追踪：非法入参返回携带稳定原因码的拒绝，不抛异常 */
    public QueryOutcome<FundFlow> summarizeOutcome(String address, long asOf) {
        if (address == null || address.isBlank()) {
            return QueryOutcome.rejected(new QueryRejection(
                    QueryRejectReason.INVALID_ADDRESS,
                    "address 不能为空或全空白，实际: "
                            + (address == null ? "null" : "'" + address + "'")));
        }
        if (asOf < 0) {
            return QueryOutcome.rejected(new QueryRejection(
                    QueryRejectReason.INVALID_AS_OF, "asOf 不能为负数，实际: " + asOf));
        }
        return QueryOutcome.ok(doSummarize(address, asOf));
    }

    private FundFlow doSummarize(String address, long asOf) {
        RiskPropagationService.GraphView view = snapshotView(asOf);

        long totalInflow = 0L;
        long totalOutflow = 0L;
        List<String> inflow = new java.util.ArrayList<>();
        List<String> outflow = new java.util.ArrayList<>();

        for (Transaction tx : view.transactions) {
            if (address.equals(tx.toAddress())) {
                totalInflow += tx.amount();
                inflow.add(tx.txId());
            }
            if (address.equals(tx.fromAddress())) {
                totalOutflow += tx.amount();
                outflow.add(tx.txId());
            }
        }
        FundFlow flow = new FundFlow(address, asOf, totalInflow, totalOutflow,
                List.copyOf(inflow), List.copyOf(outflow));
        log.info("资金追踪 address={}, asOf={}, 流入={} 笔共{}, 流出={} 笔共{}",
                address, asOf, inflow.size(), totalInflow, outflow.size(), totalOutflow);
        return flow;
    }

    public RiskPropagationService.GraphView snapshotView(long asOf) {
        return propagationService.buildSnapshot(asOf);
    }
}
