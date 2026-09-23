package com.github.xingjianluo.onchainriskgraphjava.tests;

import com.github.xingjianluo.onchainriskgraphjava.domain.IngestResult;
import com.github.xingjianluo.onchainriskgraphjava.domain.IngestStatus;
import com.github.xingjianluo.onchainriskgraphjava.domain.Transaction;
import com.github.xingjianluo.onchainriskgraphjava.exception.ConflictingTransactionException;
import com.github.xingjianluo.onchainriskgraphjava.ledger.InMemoryTransactionLedger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖：并发/重复投递幂等、同 id 不同内容冲突拒绝、失败原因可区分。
 * 运行时在日志中打印交易流水（[交易流水] 前缀）。
 */
class IdempotentIngestTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotentIngestTest.class);

    private Transaction tx(String id, String from, String to, String amount, long ts) {
        return new Transaction(id, from, to, new BigDecimal(amount), ts);
    }

    @Test
    void concurrentDuplicateSubmissions_ingestedExactlyOnce() throws Exception {
        InMemoryTransactionLedger ledger = new InMemoryTransactionLedger();
        Transaction tx = tx("tx-1", "A", "B", "100.00", 1_000L);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<IngestResult>> tasks = IntStream.range(0, threads)
                    .<Callable<IngestResult>>mapToObj(i -> () -> ledger.ingest(tx))
                    .toList();
            List<Future<IngestResult>> futures = pool.invokeAll(tasks);

            int accepted = 0;
            int duplicate = 0;
            for (Future<IngestResult> f : futures) {
                IngestResult r = f.get();
                if (r.status() == IngestStatus.ACCEPTED) {
                    accepted++;
                    assertTrue(r.applied());
                } else {
                    duplicate++;
                    assertFalse(r.applied());
                }
            }

            log.info("[测试断言] 并发线程数={} ACCEPTED={} DUPLICATE={} 账本交易数={}",
                    threads, accepted, duplicate, ledger.size());
            assertEquals(1, accepted, "同一笔交易并发提交只能有一次 ACCEPTED");
            assertEquals(threads - 1, duplicate, "其余全部为幂等 DUPLICATE");
            assertEquals(1, ledger.size(), "资金流不得重复计入");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sameIdDifferentContent_isRejectedWithConflictDistinctFromDuplicate() {
        InMemoryTransactionLedger ledger = new InMemoryTransactionLedger();
        Transaction first = tx("tx-2", "A", "B", "100.00", 1_000L);

        assertEquals(IngestStatus.ACCEPTED, ledger.ingest(first).status());

        // 内容完全一致 => 普通重复投递，成功且可区分
        IngestResult dup = ledger.ingest(tx("tx-2", "A", "B", "100.00", 1_000L));
        assertEquals(IngestStatus.DUPLICATE, dup.status());
        assertFalse(dup.applied());
        log.info("[测试断言] 普通重复投递: status={} applied={} message={}",
                dup.status(), dup.applied(), dup.message());

        // 同 txId 但金额不一致 => 冲突拒绝（409 CONFLICT），不是幂等成功
        Transaction conflict = tx("tx-2", "A", "B", "999.00", 1_000L);
        ConflictingTransactionException ex = assertThrows(ConflictingTransactionException.class,
                () -> ledger.ingest(conflict));
        log.info("[测试断言] 冲突提交被拒绝: code={} message={}", ex.getCode(), ex.getMessage());
        assertEquals("CONFLICT", ex.getCode());
        assertEquals(409, ex.httpStatus());

        // 付款方不一致同样冲突
        assertThrows(ConflictingTransactionException.class,
                () -> ledger.ingest(tx("tx-2", "A", "C", "100.00", 1_000L)));

        // 冲突交易不得入账，账本仍是 1 笔
        assertEquals(1, ledger.size());
    }
}
