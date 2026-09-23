package com.github.xingjianluo.onchainriskgraphjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端 HTTP 语义：幂等成功(200/DUPLICATE) 与 冲突(409/CONFLICT)、
 * 非法规则(422/INVALID_RULE) 必须可区分；快照风险结论可通过 REST 获取。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void ingestDuplicateAndConflictAreDistinguishableOverHttp() throws Exception {
        String tx = "{\"txId\":\"w1\",\"fromAddress\":\"A\",\"toAddress\":\"B\","
                + "\"amount\":100,\"timestamp\":1000}";

        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(tx))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.applied").value(true));

        // 重复投递：200 + DUPLICATE
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(tx))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"))
                .andExpect(jsonPath("$.applied").value(false));

        // 内容冲突：409 + CONFLICT
        String conflict = "{\"txId\":\"w1\",\"fromAddress\":\"A\",\"toAddress\":\"B\","
                + "\"amount\":999,\"timestamp\":1000}";
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(conflict))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void invalidRuleRejectedOverHttp() throws Exception {
        String badRule = "{\"version\":\"web-bad\",\"maxHops\":3,\"decay\":2.0,"
                + "\"threshold\":0.5,\"effectiveFrom\":0,\"note\":\"x\"}";
        mvc.perform(post("/api/rules").contentType(MediaType.APPLICATION_JSON).content(badRule))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_RULE"));
    }

    @Test
    void snapshotRiskQueryWorksEndToEnd() throws Exception {
        // 标记 S，建立 S->A 流水
        mvc.perform(post("/api/labels").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"S\",\"baseScore\":1.0,\"reason\":\"sdn\",\"timestamp\":100}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"txId\":\"w2\",\"fromAddress\":\"S\",\"toAddress\":\"A\","
                                + "\"amount\":10,\"timestamp\":200}"))
                .andExpect(status().isOk());

        // asOf=500 可见：A 1 跳 0.5，达到阈值
        mvc.perform(get("/api/addresses/A/risk").param("asOf", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(0.5))
                .andExpect(jsonPath("$.risky").value(true))
                .andExpect(jsonPath("$.ruleVersion").value("default-v1"))
                .andExpect(jsonPath("$.paths[0].sourceAddress").value("S"));

        // asOf=150 交易不可见：无风险
        mvc.perform(get("/api/addresses/A/risk").param("asOf", "150"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(0.0))
                .andExpect(jsonPath("$.risky").value(false));
    }
}
