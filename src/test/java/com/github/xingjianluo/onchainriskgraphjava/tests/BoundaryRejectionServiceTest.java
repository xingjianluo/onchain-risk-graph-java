package com.github.xingjianluo.onchainriskgraphjava.tests;

import com.github.xingjianluo.onchainriskgraphjava.ledger.TransactionLedger;
import com.github.xingjianluo.onchainriskgraphjava.model.RejectReason;
import com.github.xingjianluo.onchainriskgraphjava.query.QueryInvalidException;
import com.github.xingjianluo.onchainriskgraphjava.query.QueryNotFoundException;
import com.github.xingjianluo.onchainriskgraphjava.service.BatchReplayService;
import com.github.xingjianluo.onchainriskgraphjava.service.FundFlowService;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskLabelRegistry;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskPropagationService;
import com.github.xingjianluo.onchainriskgraphjava.service.RuleReplayService;
import com.github.xingjianluo.onchainriskgraphjava.service.RuleService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 边界拒绝（服务层直接断言）：空白地址 / 非法时间点 / 无生效规则 / 未知版本，
 * 原因码彼此可区分，且异常仍兼容旧的 IllegalArgumentException/NoSuchElementException 断言。
 */
class BoundaryRejectionServiceTest {

    private static final Logger log = LoggerFactory.getLogger(BoundaryRejectionServiceTest.class);

    private final RuleService rules = new RuleService();
    private final RiskPropagationService propagation =
            new RiskPropagationService(new TransactionLedger(), new RiskLabelRegistry(), rules);
    private final FundFlowService funds = new FundFlowService(propagation);
    private final RuleReplayService replay = new RuleReplayService(rules, propagation);
    private final BatchReplayService batch = new BatchReplayService(propagation);

    @Test
    void blankAddress_distinctReasons() {
        QueryInvalidException nullAddr = assertThrows(QueryInvalidException.class,
                () -> propagation.evaluate(null, 100L));
        QueryInvalidException blankAddr = assertThrows(QueryInvalidException.class,
                () -> propagation.evaluate("   ", 100L));
        QueryInvalidException fundsBlank = assertThrows(QueryInvalidException.class,
                () -> funds.summarize("\t", 100L));

        assertEquals(RejectReason.BLANK_ADDRESS, nullAddr.reason());
        assertEquals(RejectReason.BLANK_ADDRESS, blankAddr.reason());
        assertEquals(RejectReason.BLANK_ADDRESS, fundsBlank.reason());
        assertTrue(blankAddr instanceof IllegalArgumentException,
                "保持旧的 IllegalArgumentException 兼容语义");
        log.info("[服务边界] null/空白/制表符地址 => {} ({}): {}",
                blankAddr.reason().code(), blankAddr.reason().httpStatus(), blankAddr.getMessage());
    }

    @Test
    void invalidAsOf_distinctFromMissingAndNoRule() {
        QueryInvalidException negative = assertThrows(QueryInvalidException.class,
                () -> propagation.evaluate("A", -5L));
        assertEquals(RejectReason.INVALID_AS_OF, negative.reason());

        // 无任何规则：NO_EFFECTIVE_RULE，且仍是 NoSuchElementException 子类（老用例不断言回归）
        QueryNotFoundException noRule = assertThrows(QueryNotFoundException.class,
                () -> propagation.evaluate("A", 0L));
        assertEquals(RejectReason.NO_EFFECTIVE_RULE, noRule.reason());
        assertTrue(noRule instanceof java.util.NoSuchElementException);
        log.info("[服务边界] 负 asOf => {}；无生效规则 => {}",
                negative.reason().code(), noRule.reason().code());
    }

    @Test
    void unknownVersionAndBatchReasons_areDistinct() {
        rules.publish(3, 0.5, 0.5, 0L);
        QueryNotFoundException unknown = assertThrows(QueryNotFoundException.class,
                () -> replay.evaluateWithVersion("A", 0L, 7));
        assertEquals(RejectReason.UNKNOWN_RULE_VERSION, unknown.reason());

        QueryInvalidException emptyBatch = assertThrows(QueryInvalidException.class,
                () -> batch.replay(java.util.List.of()));
        assertEquals(RejectReason.INVALID_BATCH, emptyBatch.reason());

        QueryInvalidException nullAsOfPoint = assertThrows(QueryInvalidException.class,
                () -> batch.replay(java.util.List.of(
                        new com.github.xingjianluo.onchainriskgraphjava.model.ReplayPoint("A", null))));
        assertEquals(RejectReason.INVALID_BATCH, nullAsOfPoint.reason());
        log.info("[服务边界] 未知版本 => {}；空批次/点位缺 asOf => {}",
                unknown.reason().code(), emptyBatch.reason().code());
    }
}
