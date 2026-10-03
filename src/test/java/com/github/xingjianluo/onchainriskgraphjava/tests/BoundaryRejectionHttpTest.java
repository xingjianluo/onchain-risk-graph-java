package com.github.xingjianluo.onchainriskgraphjava.tests;

import com.github.xingjianluo.onchainriskgraphjava.controller.QueryExceptionAdvice;
import com.github.xingjianluo.onchainriskgraphjava.controller.RiskGraphController;
import com.github.xingjianluo.onchainriskgraphjava.ledger.TransactionLedger;
import com.github.xingjianluo.onchainriskgraphjava.service.BatchReplayService;
import com.github.xingjianluo.onchainriskgraphjava.service.FundFlowService;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskLabelRegistry;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskPropagationService;
import com.github.xingjianluo.onchainriskgraphjava.service.RuleReplayService;
import com.github.xingjianluo.onchainriskgraphjava.service.RuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 边界查询的“可解释拒绝”HTTP 测试：
 * 每类边界都返回彼此可区分的稳定 reasonCode 与固定 HTTP 状态，正常路径不受影响。
 *
 * <p>规则 v1 effectiveAt=100，因此 asOf<100 的查询落在“没有任何生效规则”区间。
 */
class BoundaryRejectionHttpTest {

    private static final Logger log = LoggerFactory.getLogger(BoundaryRejectionHttpTest.class);

    private MockMvc mockMvc;
    private RuleService rules;

    @BeforeEach
    void setUp() {
        TransactionLedger ledger = new TransactionLedger();
        rules = new RuleService();
        RiskLabelRegistry labels = new RiskLabelRegistry();
        RiskPropagationService propagation = new RiskPropagationService(ledger, labels, rules);
        FundFlowService funds = new FundFlowService(propagation);
        RuleReplayService replay = new RuleReplayService(rules, propagation);
        BatchReplayService batch = new BatchReplayService(propagation);
        rules.publish(3, 0.5, 0.5, 100L); // v1 自 t=100 起生效
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RiskGraphController(ledger, labels, rules, propagation,
                        funds, replay, batch))
                .setControllerAdvice(new QueryExceptionAdvice())
                .build();
    }

    @Test
    void blankAddress_isRejectedAs_BLANK_ADDRESS_400() throws Exception {
        // 地址通过查询参数承载，纯空白必须被识别为 BLANK_ADDRESS
        expectReject(get("/api/risk/diff").param("asOf", "200")
                .param("versionA", "1").param("versionB", "1")
                .param("address", "	 "), 400, "BLANK_ADDRESS");
        log.info("[边界1-HTTP] 对比接口空白地址 => 400 BLANK_ADDRESS；空白地址直查路径见服务层测试");
    }

    @Test
    void noEffectiveRule_isRejectedAs_NO_EFFECTIVE_RULE_404() throws Exception {
        expectReject(get("/api/risk/B").param("asOf", "50"), 404, "NO_EFFECTIVE_RULE");
        expectReject(get("/api/risk").param("asOf", "99"), 404, "NO_EFFECTIVE_RULE");
        log.info("[边界2] 时间点之前无生效规则(v1 自 100 起) => 404 NO_EFFECTIVE_RULE");
    }

    @Test
    void invalidAsOf_isRejectedAs_INVALID_AS_OF_400() throws Exception {
        expectReject(get("/api/risk/B").param("asOf", "-1"), 400, "INVALID_AS_OF");
        expectReject(get("/api/risk/B").param("asOf", "abc"), 400, "INVALID_AS_OF");
        log.info("[边界3] asOf 负数/不可解析 => 400 INVALID_AS_OF");
    }

    @Test
    void missingAsOf_isRejectedAs_MISSING_AS_OF_400() throws Exception {
        expectReject(get("/api/risk/B"), 400, "MISSING_AS_OF");
        expectReject(get("/api/funds/B"), 400, "MISSING_AS_OF");
        log.info("[边界4] 缺少 asOf => 400 MISSING_AS_OF（与取值非法可区分）");
    }

    @Test
    void unknownRuleVersion_isRejectedAs_UNKNOWN_RULE_VERSION_404() throws Exception {
        expectReject(get("/api/risk/B").param("asOf", "200").param("ruleVersion", "9"),
                404, "UNKNOWN_RULE_VERSION");
        expectReject(get("/api/risk/B").param("asOf", "200").param("ruleVersion", "0"),
                404, "UNKNOWN_RULE_VERSION");
        log.info("[边界5] 显式指定不存在/非法的规则版本 => 404 UNKNOWN_RULE_VERSION");
    }

    @Test
    void diffMissingVersionParams_isRejectedAs_INVALID_PARAMETER_400() throws Exception {
        expectReject(get("/api/risk/diff").param("asOf", "200").param("versionA", "1"),
                400, "INVALID_PARAMETER");
        expectReject(get("/api/risk/diff").param("asOf", "-1").param("versionA", "1")
                .param("versionB", "2"), 400, "INVALID_AS_OF");
        log.info("[边界6] 版本对比缺少版本参数 => 400 INVALID_PARAMETER；asOf 非法仍归 INVALID_AS_OF");
    }

    @Test
    void batchEndpoint_rejectsInvalidBatchAndMalformedBody() throws Exception {
        // 空批次
        mockMvc.perform(post("/api/risk/replay").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"points\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasonCode").value("INVALID_BATCH"));
        // 点位缺 asOf
        mockMvc.perform(post("/api/risk/replay").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"points\":[{\"address\":\"A\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasonCode").value("INVALID_BATCH"));
        // 非法 JSON
        mockMvc.perform(post("/api/risk/replay").contentType(MediaType.APPLICATION_JSON)
                        .content("not-a-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasonCode").value("INVALID_PARAMETER"));
        // 合法批次正常 200
        mockMvc.perform(post("/api/risk/replay").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"points\":[{\"address\":\"A\",\"asOf\":200}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(1))
                .andExpect(jsonPath("$.points[0].conclusions[0].address").value("A"));
        log.info("[边界7] 批量入口：空批次/缺asOf=>INVALID_BATCH，坏JSON=>INVALID_PARAMETER，正常=>200");
    }

    @Test
    void normalQueryPath_isNotAffected() throws Exception {
        // 正常查询在拒绝逻辑接入后依旧 200
        mockMvc.perform(get("/api/risk/B").param("asOf", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("B"))
                .andExpect(jsonPath("$.ruleVersion").value(1));
        // 资金追踪正常 200
        mockMvc.perform(get("/api/funds/B").param("asOf", "200"))
                .andExpect(status().isOk());
        log.info("[正常路径] 合法查询仍 200，边界拒绝不影响正常评估");
    }

    private void expectReject(
            org.springframework.test.web.servlet.RequestBuilder request,
            int httpStatus, String reasonCode) throws Exception {
        ResultActions actions = mockMvc.perform(request)
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.reasonCode").value(reasonCode))
                .andExpect(jsonPath("$.reason").value(reasonCode))
                .andExpect(jsonPath("$.message").isNotEmpty());
        log.info("  拒绝响应 http={}, body={}", httpStatus,
                actions.andReturn().getResponse().getContentAsString());
    }
}
