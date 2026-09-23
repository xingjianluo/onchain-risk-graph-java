package com.github.xingjianluo.onchainriskgraphjava.tests;

import com.github.xingjianluo.onchainriskgraphjava.ledger.TransactionLedger;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitResult;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitStatus;
import com.github.xingjianluo.onchainriskgraphjava.model.Transaction;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发 / 重复 / 冲突入账测试。
 */
class IdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyTest.class);

    private final TransactionLedger ledger = new TransactionLedger();

    @Test
    void concurrentDuplicateSubmission_countedExactlyOnce() throws Exception {
        String txId = "tx-001";
        Transaction tx = new Transaction(txId, "A", "B", 100, 1000L);

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger duplicated = new AtomicInteger();
        List<SubmitResult<Transaction>> results = new ArrayList<>();
        Object lock = new Object();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    SubmitResult<Transaction> r = ledger.submit(tx);
                    synchronized (lock) {
                        results.add(r);
                    }
                    if (r.status() == SubmitStatus.ACCEPTED) {
                        accepted.incrementAndGet();
                    } else if (r.status() == SubmitStatus.DUPLICATE) {
                        duplicated.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "并发任务应在时限内完成");
        pool.shutdown();

        log.info("========== 并发重复入账：交易流水（t=1000 可见）==========");
        ledger.visibleTransactions(1000L).forEach(t ->
                log.info("  txId={}, {} -> {}, amount={}, visibleAt={}",
                        t.txId(), t.fromAddress(), t.toAddress(), t.amount(), t.visibleAt()));
        log.info("========== 20 个并发提交结果：ACCEPTED={}, DUPLICATE={} ==========",
                accepted.get(), duplicated.get());
        results.forEach(r -> log.info("  status={}, message={}", r.status(), r.message()));

        assertEquals(1, accepted.get(), "恰好一笔首次入账成功");
        assertEquals(threads - 1, duplicated.get(), "其余全部为普通重复投递");
        assertEquals(1, ledger.visibleCount(1000L), "资金流只计入一次");

        // 串行再提交一次，依旧幂等
        SubmitResult<Transaction> again = ledger.submit(tx);
        assertEquals(SubmitStatus.DUPLICATE, again.status());
    }

    @Test
    void sameIdWithDifferentContent_rejectedWithDistinguishableReason() {
        Transaction first = new Transaction("tx-002", "A", "B", 100, 1000L);
        Transaction conflicting = new Transaction("tx-002", "A", "C", 100, 1000L);
        Transaction sameAgain = new Transaction("tx-002", "A", "B", 100, 1000L);

        SubmitResult<Transaction> r1 = ledger.submit(first);
        SubmitResult<Transaction> r2 = ledger.submit(conflicting);
        SubmitResult<Transaction> r3 = ledger.submit(sameAgain);

        log.info("========== 重复 vs 冲突 失败原因可区分 ==========");
        log.info("首次提交: status={}, message={}", r1.status(), r1.message());
        log.info("内容冲突: status={}, message={}", r2.status(), r2.message());
        log.info("内容一致: status={}, message={}", r3.status(), r3.message());

        assertEquals(SubmitStatus.ACCEPTED, r1.status());
        assertEquals(SubmitStatus.REJECTED_CONFLICT, r2.status());
        assertEquals(SubmitStatus.DUPLICATE, r3.status());
        assertNotEquals(r2.status(), r3.status(), "冲突与普通重复必须可区分");
        assertTrue(r2.message().contains("toAddress"), "冲突原因需指出差异字段");
        assertEquals(1, ledger.visibleCount(1000L), "冲突交易不得进入账本");
    }

    @Test
    void invalidTransaction_rejected() {
        SubmitResult<Transaction> r = ledger.submit(new Transaction("tx-003", "A", "B", 0, 1L));
        log.info("非法交易: status={}, message={}", r.status(), r.message());
        assertEquals(SubmitStatus.REJECTED_INVALID, r.status());
    }
}
