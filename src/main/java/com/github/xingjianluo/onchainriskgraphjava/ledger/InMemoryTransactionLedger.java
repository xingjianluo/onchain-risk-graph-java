package com.github.xingjianluo.onchainriskgraphjava.ledger;

import com.github.xingjianluo.onchainriskgraphjava.domain.IngestResult;
import com.github.xingjianluo.onchainriskgraphjava.domain.IngestStatus;
import com.github.xingjianluo.onchainriskgraphjava.domain.Transaction;
import com.github.xingjianluo.onchainriskgraphjava.exception.ConflictingTransactionException;
import com.github.xingjianluo.onchainriskgraphjava.exception.InvalidInputException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存交易账本。
 *
 * 语义：
 * 1. 以 txId 为幂等键：并发或重复提交同一笔交易，只有第一次计入资金流，其余为 DUPLICATE。
 * 2. txId 相同但字段（付款方/收款方/金额/时间）不一致，视为冲突，抛
 *    {@link ConflictingTransactionException}（HTTP 409, code=CONFLICT），
 *    与普通重复投递（HTTP 200 + status=DUPLICATE）明确区分。
 * 3. 只追加、不修改、不删除；快照查询按事件时间过滤，后续写入不影响历史时点结论。
 */
@Component
public class InMemoryTransactionLedger {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTransactionLedger.class);

    /** 按 txId 去重的存储；putIfAbsent 保证并发下同一笔交易至多计入一次。 */
    private final ConcurrentMap<String, Transaction> byTxId = new ConcurrentHashMap<>();

    /**
     * 入账一笔交易。
     *
     * @return ACCEPTED（首次计入）或 DUPLICATE（内容一致的重复投递，applied=false）
     * @throws InvalidInputException            入参非法
     * @throws ConflictingTransactionException  txId 相同但内容不一致
     */
    public IngestResult ingest(Transaction tx) {
        validate(tx);

        Transaction existing = byTxId.putIfAbsent(tx.txId(), tx);
        if (existing == null) {
            log.info("[交易流水] 首次入账 ACCEPTED txId={} {} --{}--> {} @ {}",
                    tx.txId(), tx.fromAddress(), tx.amount(), tx.toAddress(), tx.timestamp());
            return new IngestResult(tx.txId(), IngestStatus.ACCEPTED, true,
                    "交易首次入账，资金流与风险指标已计入一次");
        }

        // record 的 equals 比较全部字段：内容一致 => 幂等忽略；不一致 => 冲突拒绝
        if (existing.equals(tx)) {
            log.info("[交易流水] 重复投递幂等忽略 DUPLICATE txId={}（内容一致，未重复计入资金流）", tx.txId());
            return new IngestResult(tx.txId(), IngestStatus.DUPLICATE, false,
                    "内容完全一致的重复投递，已幂等忽略，未重复计入资金流与风险指标");
        }

        log.warn("[交易流水] 标识冲突拒绝 CONFLICT txId={} 已存=[{}] 新提交=[{}]",
                tx.txId(), describe(existing), describe(tx));
        throw new ConflictingTransactionException(tx.txId(), describe(existing), describe(tx));
    }

    /**
     * 返回某时点可见的全部交易（timestamp &lt;= asOf），按 (时间, txId) 排序保证确定性。
     * 该时点之后写入的交易不会出现，保证快照结论不受未来写入影响。
     */
    public List<Transaction> visibleAt(long asOf) {
        return byTxId.values().stream()
                .filter(tx -> tx.timestamp() <= asOf)
                .sorted(Comparator.comparingLong(Transaction::timestamp)
                        .thenComparing(Transaction::txId))
                .toList();
    }

    /** 当前账本中不同交易的数量（重复投递不增加）。 */
    public int size() {
        return byTxId.size();
    }

    private void validate(Transaction tx) {
        if (tx == null) {
            throw new InvalidInputException("交易体不能为空");
        }
        if (isBlank(tx.txId())) {
            throw new InvalidInputException("txId 不能为空");
        }
        if (isBlank(tx.fromAddress())) {
            throw new InvalidInputException("fromAddress 不能为空, txId=" + tx.txId());
        }
        if (isBlank(tx.toAddress())) {
            throw new InvalidInputException("toAddress 不能为空, txId=" + tx.txId());
        }
        if (tx.amount() == null || tx.amount().signum() <= 0) {
            throw new InvalidInputException("amount 必须为正数, txId=" + tx.txId());
        }
        if (tx.timestamp() <= 0) {
            throw new InvalidInputException("timestamp 必须为正数(epoch millis), txId=" + tx.txId());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String describe(Transaction tx) {
        return tx.fromAddress() + "->" + tx.toAddress() + " " + tx.amount() + " @" + tx.timestamp();
    }
}
