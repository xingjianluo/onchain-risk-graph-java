package com.github.xingjianluo.onchainriskgraphjava.service;

import com.github.xingjianluo.onchainriskgraphjava.domain.IngestResult;
import com.github.xingjianluo.onchainriskgraphjava.domain.RiskConclusion;
import com.github.xingjianluo.onchainriskgraphjava.domain.RiskLabel;
import com.github.xingjianluo.onchainriskgraphjava.domain.Transaction;
import com.github.xingjianluo.onchainriskgraphjava.exception.InvalidInputException;
import com.github.xingjianluo.onchainriskgraphjava.ledger.InMemoryTransactionLedger;
import com.github.xingjianluo.onchainriskgraphjava.ledger.RiskLabelBook;
import com.github.xingjianluo.onchainriskgraphjava.propagation.RiskPropagator;
import com.github.xingjianluo.onchainriskgraphjava.rule.RiskRule;
import com.github.xingjianluo.onchainriskgraphjava.rule.RuleRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 应用服务门面。
 *
 * 快照语义：风险查询 / 流水回放都以 asOf 为上界，只读取 timestamp &lt;= asOf
 * 的交易与标记；账本只追加，因此查询时点之后的写入不会改变历史结论。
 *
 * 规则复现：ruleVersion 为空时按 asOf 选择当时生效的规则；显式指定版本时，
 * 用该历史版本规则 + 当时可见数据复现结论（结论中携带 ruleVersion）。
 */
@Service
public class RiskGraphService {

    private static final Logger log = LoggerFactory.getLogger(RiskGraphService.class);

    private final InMemoryTransactionLedger ledger;
    private final RiskLabelBook labelBook;
    private final RuleRegistry ruleRegistry;
    private final RiskPropagator propagator;

    public RiskGraphService(InMemoryTransactionLedger ledger,
                            RiskLabelBook labelBook,
                            RuleRegistry ruleRegistry,
                            RiskPropagator propagator) {
        this.ledger = ledger;
        this.labelBook = labelBook;
        this.ruleRegistry = ruleRegistry;
        this.propagator = propagator;
    }

    /** 交易入账（幂等 + 冲突拒绝，详见账本实现）。 */
    public IngestResult ingest(Transaction tx) {
        return ledger.ingest(tx);
    }

    /** 添加风险源头标记。 */
    public void addLabel(RiskLabel label) {
        labelBook.add(label);
    }

    /** 注册新规则版本（非法/冲突规则会被拒绝，不会静默生效）。 */
    public RiskRule registerRule(RiskRule rule) {
        return ruleRegistry.register(rule);
    }

    /**
     * 查询某地址在 asOf 时点的风险结论。
     *
     * @param address     目标地址
     * @param asOf        快照时点（epoch millis，含）
     * @param ruleVersion 可空：空=当时生效规则；非空=用指定版本规则复现历史结论
     */
    public RiskConclusion riskOf(String address, long asOf, String ruleVersion) {
        if (address == null || address.isBlank()) {
            throw new InvalidInputException("address 不能为空");
        }
        if (asOf <= 0) {
            throw new InvalidInputException("asOf 必须为正数(epoch millis)");
        }
        List<Transaction> txs = ledger.visibleAt(asOf);
        List<RiskLabel> labels = labelBook.visibleAt(asOf);
        RiskRule rule = (ruleVersion == null || ruleVersion.isBlank())
                ? ruleRegistry.ruleAt(asOf)
                : ruleRegistry.ruleByVersion(ruleVersion);

        log.info("[快照查询] address={} asOf={} ruleVersion={} 可见交易数={} 可见标记数={}",
                address, asOf, rule.version(), txs.size(), labels.size());
        return propagator.evaluate(address, asOf, txs, labels, rule);
    }

    /** 流水回放：返回 asOf 时点之前可见的全部交易（按时间顺序），用于核验快照语义。 */
    public List<Transaction> replay(long asOf) {
        if (asOf <= 0) {
            throw new InvalidInputException("asOf 必须为正数(epoch millis)");
        }
        List<Transaction> txs = ledger.visibleAt(asOf);
        log.info("[流水回放] asOf={} 可见交易数={}", asOf, txs.size());
        return txs;
    }
}
