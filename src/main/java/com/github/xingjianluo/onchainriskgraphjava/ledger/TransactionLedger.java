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

    public SubmitResult<Transaction> submit(Transaction tx) {
        String invalid = validate(tx);
        if (invalid != null) {
            log.warn("交易非法被拒绝: tx={}, 原因={}", tx, invalid);
            return new SubmitResult<>(SubmitStatus.REJECTED_INVALID, invalid, null);
        }

        Transaction existing = byTxId.putIfAbsent(tx.txId(), tx);
        if (existing == null) {
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
        return filterVisible(snapshotTransactions(), asOf);
    }

    /**
     * 采集时刻账本的全量不可变副本（已幂等去重）。
     * 批量回放开始时采集一次；采集之后并发入账的交易不会进入该副本，
     * 哪怕其 visibleAt 自称在过去。
     */
    public List<Transaction> snapshotTransactions() {
        // ConcurrentHashMap#values 的迭代为弱一致：反映的是迭代期间某一时刻的账本状态，
        // 先落到本地 List 再冻结，快照一旦形成就与后续写入彻底脱钩。
        return List.copyOf(byTxId.values());
    }

    /** 在给定快照副本上按 visibleAt &lt;= asOf 过滤并确定性排序 */
    public static List<Transaction> filterVisible(List<Transaction> snapshot, long asOf) {
        return snapshot.stream()
                .filter(t -> t.visibleAt() <= asOf)
                .sorted(Comparator.comparingLong(Transaction::visibleAt)
                        .thenComparing(Transaction::txId))
                .toList();
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
