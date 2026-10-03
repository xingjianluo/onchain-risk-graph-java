package com.github.xingjianluo.onchainriskgraphjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 边界查询的可解释拒绝：每类问题都有稳定、彼此可区分的原因码与 HTTP 语义；
 * 正常查询路径不受影响。
 *
 * <p>初始规则生效点配置为 t=1000，因此 t<1000 的查询触发 NO_EFFECTIVE_RULE。
 */
@SpringBootTest(properties = "risk.initial-effective-at=1000")
@AutoConfigureMockMvc
class BoundaryRejectionTest {

    private static final Logger log = LoggerFactory.getLogger(BoundaryRejectionTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void eachBoundaryRejectionIsDistinctAndExplainable() throws Exception {
        List<Expectation> cases = List.of(
                new Expectation("空白地址", get("/api/risk/{a}", " ").param("asOf", "2000"),
                        400, "INVALID_ADDRESS"),
                new Expectation("负时间点", get("/api/risk/A").param("asOf", "-5"),
                        400, "INVALID_AS_OF"),
                new Expectation("非法版本号", get("/api/risk/A").param("asOf", "2000")
                        .param("ruleVersion", "0"), 400, "INVALID_RULE_VERSION"),
                new Expectation("版本不存在", get("/api/risk/A").param("asOf", "2000")
                        .param("ruleVersion", "99"), 422, "UNKNOWN_RULE_VERSION"),
                new Expectation("无生效规则", get("/api/risk/A").param("asOf", "500"),
                        422, "NO_EFFECTIVE_RULE"),
                new Expectation("空回放请求", post("/api/risk/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"A\",\"asOfs\":[]}"),
                        422, "EMPTY_REPLAY_REQUEST"),
                new Expectation("资金追踪空白地址", get("/api/funds/{a}", " ").param("asOf", "2000"),
                        400, "INVALID_ADDRESS"));

        Set<String> reasons = new HashSet<>();
        for (Expectation c : cases) {
            MvcResult result = mockMvc.perform(c.request())
                    .andExpect(status().is(c.httpStatus()))
                    .andExpect(jsonPath("$.reason").value(c.reason()))
                    .andExpect(jsonPath("$.message").isNotEmpty())
                    .andReturn();
            String body = result.getResponse().getContentAsString();
            log.info("[边界拒绝] 场景={} -> HTTP {}, 响应={}", c.name(), c.httpStatus(), body);
            reasons.add(c.reason());
        }
        // 六类场景覆盖六种不同的原因码，彼此可区分
        assertEquals(6, reasons.size(), "每类边界问题的原因码必须可区分");
    }

    @Test
    void normalQueriesAreNotAffectedByRejectionHandling() throws Exception {
        // 正常入账 + 标记 + 有效时点查询，全部按原语义工作
        mockMvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"txId\":\"b1\",\"fromAddress\":\"S\",\"toAddress\":\"A\","
                                + "\"amount\":100,\"visibleAt\":1500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        mockMvc.perform(post("/api/labels").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"S\",\"baseScore\":1.0,\"effectiveAt\":1000}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/risk/A").param("asOf", "1500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.risky").value(true))
                .andExpect(jsonPath("$.ruleVersion").value(1));
        mockMvc.perform(get("/api/funds/A").param("asOf", "1500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalInflow").value(100));
        // 显式指定已发布版本也正常
        mockMvc.perform(get("/api/risk/A").param("asOf", "1500").param("ruleVersion", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleVersion").value(1));
        log.info("[正常路径] 入账/标记/风险评估/资金追踪/显式版本查询均正常，拒绝逻辑不影响正常查询");
        assertTrue(true);
    }

    @Test
    void rejectionReasonsAreStableEnumValues() throws Exception {
        MvcResult r1 = mockMvc.perform(get("/api/risk/A").param("asOf", "500")).andReturn();
        MvcResult r2 = mockMvc.perform(get("/api/risk/A").param("asOf", "500")).andReturn();
        assertEquals(r1.getResponse().getContentAsString(), r2.getResponse().getContentAsString(),
                "同一边界问题的拒绝响应必须稳定可重复");
        assertFalse(r1.getResponse().getContentAsString().isBlank());
        log.info("[稳定性] 同一拒绝场景两次响应完全一致: {}", r1.getResponse().getContentAsString());
    }

    private record Expectation(String name,
                               org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               int httpStatus, String reason) {
    }
}
