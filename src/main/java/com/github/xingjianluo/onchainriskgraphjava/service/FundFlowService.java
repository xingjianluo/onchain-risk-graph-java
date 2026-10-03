package com.github.xingjianluo.onchainriskgraphjava.service;

import com.github.xingjianluo.onchainriskgraphjava.model.FundFlow;
import com.github.xingjianluo.onchainriskgraphjava.model.Transaction;
import com.github.xingjianluo.onchainriskgraphjava.query.QueryValidator;
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
        String cleanAddress = QueryValidator.requireAddress(address);
        long validAsOf = QueryValidator.requireAsOf(asOf);
        RiskPropagationService.GraphView view = snapshotView(validAsOf);

        long totalInflow = 0L;
        long totalOutflow = 0L;
        List<String> inflow = new java.util.ArrayList<>();
        List<String> outflow = new java.util.ArrayList<>();

        for (Transaction tx : view.transactions) {
            if (cleanAddress.equals(tx.toAddress())) {
                totalInflow += tx.amount();
                inflow.add(tx.txId());
            }
            if (cleanAddress.equals(tx.fromAddress())) {
                totalOutflow += tx.amount();
                outflow.add(tx.txId());
            }
        }
        FundFlow flow = new FundFlow(cleanAddress, validAsOf, totalInflow, totalOutflow,
                List.copyOf(inflow), List.copyOf(outflow));
        log.info("资金追踪 address={}, asOf={}, 流入={} 笔共{}, 流出={} 笔共{}",
                cleanAddress, validAsOf, inflow.size(), totalInflow, outflow.size(), totalOutflow);
        return flow;
    }

    public RiskPropagationService.GraphView snapshotView(long asOf) {
        return propagationService.buildSnapshot(asOf);
    }
}
