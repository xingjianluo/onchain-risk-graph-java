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
 * 端到端 HTTP 语义：首次 200、重复 200(DUPLICATE)、冲突 409、非法 400；
 * 快照风险评估走配置注入的初始规则 v1。
 */
@SpringBootTest
@AutoConfigureMockMvc
public class ApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    private String tx(String id, String from, String to, long amount, long t) {
        return "{\"txId\":\"%s\",\"fromAddress\":\"%s\",\"toAddress\":\"%s\",\"amount\":%d,\"visibleAt\":%d}"
                .formatted(id, from, to, amount, t);
    }

    @Test
    public void contentLoad() {
    }

    @Test
    void http_semantics_and_snapshotRiskFlow() throws Exception {
        // 首次入账 200 ACCEPTED
        mockMvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content(tx("http-1", "A", "B", 100, 100L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        // 重复 200 DUPLICATE
        mockMvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content(tx("http-1", "A", "B", 100, 100L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));

        // 冲突 409
        mockMvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content(tx("http-1", "A", "X", 100, 100L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("REJECTED_CONFLICT"));

        // 非法 400
        mockMvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content(tx("http-2", "A", "B", -1, 100L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED_INVALID"));

        // 标记风险源并验证传播结论（初始规则 maxHops=3 decay=0.5 threshold=0.5）
        mockMvc.perform(post("/api/labels").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"A\",\"baseScore\":1.0,\"effectiveAt\":0}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/risk/B").param("asOf", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.risky").value(true))
                .andExpect(jsonPath("$.ruleVersion").value(1))
                .andExpect(jsonPath("$.contributions[0].route[0]").value("A"))
                .andExpect(jsonPath("$.contributions[0].txIds[0]").value("http-1"));

        // t=99 时交易尚不可见 => B 无风险（快照隔离）
        mockMvc.perform(get("/api/risk/B").param("asOf", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskScore").value(0.0))
                .andExpect(jsonPath("$.risky").value(false));

        // 资金追踪
        mockMvc.perform(get("/api/funds/B").param("asOf", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalInflow").value(100));

        // 非法规则 400，不静默生效
        mockMvc.perform(post("/api/rules").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxHops\":1,\"decay\":2.0,\"riskThreshold\":0.5,\"effectiveAt\":500}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED_INVALID"));
    }
}
