package com.github.xingjianluo.onchainriskgraphjava.ledger;

import com.github.xingjianluo.onchainriskgraphjava.model.SubmitResult;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitStatus;
import com.github.xingjianluo.onchainriskgraphjava.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存交易账本：以 txId 为幂等键，线程安全地保存交易流水。
 *
 * <p>语义：
 * <ul>
 *   <li>同一 txId 且内容一致：普通重复投递，返回 {@link SubmitStatus#DUPLICATE}，不重复入账；</li>
 *   <li>同一 txId 但 from/to/amount/visibleAt 任一不一致：返回
 *       {@link SubmitStatus#REJECTED_CONFLICT}，消息中列出差异字段，与普通重复投递可区分；</li>
 *   <li>字段非法：{@link SubmitStatus#REJECTED_INVALID}。</li>
 * </ul>
 */
@Component
public class TransactionLedger {

    private static final Logger log = LoggerFactory.getLogger(TransactionLedger.class);

    private final ConcurrentMap<String, Transaction> byTxId = new ConcurrentHashMap<>();
    /** 入账顺序号（单调递增），用于批量回放时截取一致的账本视图 */
    private final AtomicLong ingestSeq = new AtomicLong(0L);
    private final ConcurrentMap<String, Long> seqByTxId = new ConcurrentHashMap<>();

    public SubmitResult<Transaction> submit(Transaction tx) {
        String invalid = validate(tx);
        if (invalid != null) {
            log.warn("交易非法被拒绝: tx={}, 原因={}", tx, invalid);
            return new SubmitResult<>(SubmitStatus.REJECTED_INVALID, invalid, null);
        }

        Transaction existing = byTxId.putIfAbsent(tx.txId(), tx);
        if (existing == null) {
            seqByTxId.put(tx.txId(), ingestSeq.incrementAndGet());
            log.info("交易入账成功 txId={}, {} -> {}, amount={}, visibleAt={}",
                    tx.txId(), tx.fromAddress(), tx.toAddress(), tx.amount(), tx.visibleAt());
            return new SubmitResult<>(SubmitStatus.ACCEPTED, "交易首次入账", tx);
        }

        if (existing.equals(tx)) {
            log.info("重复投递(内容一致)幂等忽略 txId={}", tx.txId());
            return new SubmitResult<>(SubmitStatus.DUPLICATE,
                    "普通重复投递：交易 " + tx.txId() + " 已存在且内容一致，已幂等忽略", existing);
        }

        String diff = describeDiff(existing, tx);
        log.warn("冲突被拒绝 txId={}, 差异={}", tx.txId(), diff);
        return new SubmitResult<>(SubmitStatus.REJECTED_CONFLICT,
                "内容冲突：交易标识 " + tx.txId() + " 已存在但内容不一致 [" + diff + "]，拒绝写入", existing);
    }

    /** 截至 asOf（含）可见的全部交易，按 (visibleAt, txId) 确定性排序 */
    public List<Transaction> visibleTransactions(long asOf) {
        return visibleTransactions(asOf, Long.MAX_VALUE);
    }

    /**
     * 截至 asOf（含）可见、且入账顺序号不超过 maxSeq 的交易。
     * 批量回放用 maxSeq 锁住开始时刻的账本视图，回放期间的新入账不会被算进来。
     */
    public List<Transaction> visibleTransactions(long asOf, long maxSeq) {
        return byTxId.values().stream()
                .filter(t -> t.visibleAt() <= asOf)
                .filter(t -> seqByTxId.getOrDefault(t.txId(), Long.MAX_VALUE) <= maxSeq)
                .sorted(Comparator.comparingLong(Transaction::visibleAt)
                        .thenComparing(Transaction::txId))
                .toList();
    }

    /** 当前已入账的最大顺序号（批量回放开始时截取） */
    public long currentSeq() {
        return ingestSeq.get();
    }

    public int visibleCount(long asOf) {
        return (int) byTxId.values().stream().filter(t -> t.visibleAt() <= asOf).count();
    }

    private static String validate(Transaction tx) {
        if (tx == null) {
            return "交易不能为空";
        }
        if (isBlank(tx.txId())) {
            return "txId 不能为空";
        }
        if (isBlank(tx.fromAddress())) {
            return "fromAddress 不能为空";
        }
        if (isBlank(tx.toAddress())) {
            return "toAddress 不能为空";
        }
        if (tx.amount() <= 0) {
            return "amount 必须为正数，实际: " + tx.amount();
        }
        if (tx.visibleAt() < 0) {
            return "visibleAt 不能为负数，实际: " + tx.visibleAt();
        }
        return null;
    }

    private static String describeDiff(Transaction oldTx, Transaction newTx) {
        StringBuilder sb = new StringBuilder();
        if (!oldTx.fromAddress().equals(newTx.fromAddress())) {
            sb.append("fromAddress: ").append(oldTx.fromAddress())
              .append(" != ").append(newTx.fromAddress()).append("; ");
        }
        if (!oldTx.toAddress().equals(newTx.toAddress())) {
            sb.append("toAddress: ").append(oldTx.toAddress())
              .append(" != ").append(newTx.toAddress()).append("; ");
        }
        if (oldTx.amount() != newTx.amount()) {
            sb.append("amount: ").append(oldTx.amount())
              .append(" != ").append(newTx.amount()).append("; ");
        }
        if (oldTx.visibleAt() != newTx.visibleAt()) {
            sb.append("visibleAt: ").append(oldTx.visibleAt())
              .append(" != ").append(newTx.visibleAt()).append("; ");
        }
        return sb.toString().strip();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
