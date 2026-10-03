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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 回放与对比接口的 HTTP 语义：版本对比、批量回放、显式版本复现、
 * 拒绝原因随响应返回。初始规则 v1: maxHops=3, decay=0.5, threshold=0.5, effectiveAt=0。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReplayApiTest {

    private static final Logger log = LoggerFactory.getLogger(ReplayApiTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void compareAndReplayEndpoints() throws Exception {
        // 准备数据：X -> Y -> Z，X 为风险源
        mockMvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"txId\":\"r1\",\"fromAddress\":\"X\",\"toAddress\":\"Y\","
                                + "\"amount\":100,\"visibleAt\":100}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"txId\":\"r2\",\"fromAddress\":\"Y\",\"toAddress\":\"Z\","
                                + "\"amount\":60,\"visibleAt\":200}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/labels").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"X\",\"baseScore\":1.0,\"effectiveAt\":0}"))
                .andExpect(status().isOk());
        // 发布 v2：阈值 0.5 -> 0.2
        mockMvc.perform(post("/api/rules").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxHops\":3,\"decay\":0.5,\"riskThreshold\":0.2,"
                                + "\"effectiveAt\":500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        // 版本对比：v1 -> v2 @ t=350，Z 的两跳贡献 0.25 在 v2 下越过阈值
        MvcResult compare = mockMvc.perform(get("/api/risk/compare")
                        .param("asOf", "350").param("baseVersion", "1").param("targetVersion", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value(350))
                .andExpect(jsonPath("$.baseVersion").value(1))
                .andExpect(jsonPath("$.targetVersion").value(2))
                .andExpect(jsonPath("$.becameRisky[0].address").value("Z"))
                .andExpect(jsonPath("$.becameRisky[0].baseScore").value(0.25))
                .andExpect(jsonPath("$.becameRisky[0].targetScore").value(0.25))
                .andExpect(jsonPath("$.cleared").isEmpty())
                .andReturn();
        log.info("[HTTP 对比] v1->v2 @350 响应: {}", compare.getResponse().getContentAsString());

        // 版本不存在：422 + 稳定原因码
        mockMvc.perform(get("/api/risk/compare")
                        .param("asOf", "350").param("baseVersion", "1").param("targetVersion", "9"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.reason").value("UNKNOWN_RULE_VERSION"));

        // 批量回放：顺序与请求一致（乱序请求），结论与单点一致
        MvcResult replay = mockMvc.perform(post("/api/risk/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"Z\",\"asOfs\":[350,150]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].asOf").value(350))
                .andExpect(jsonPath("$[0].outcome.payload.riskScore").value(0.25))
                .andExpect(jsonPath("$[0].outcome.payload.ruleVersion").value(1))
                .andExpect(jsonPath("$[0].outcome.payload.risky").value(false))
                .andExpect(jsonPath("$[1].asOf").value(150))
                .andExpect(jsonPath("$[1].outcome.payload.riskScore").value(0.0))
                .andReturn();
        log.info("[HTTP 回放] Z@[350,150] 响应: {}", replay.getResponse().getContentAsString());

        // 批量回放显式指定 v2：同一时点按 v2 阈值判定
        mockMvc.perform(post("/api/risk/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"Z\",\"asOfs\":[350],\"ruleVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome.payload.ruleVersion").value(2))
                .andExpect(jsonPath("$[0].outcome.payload.risky").value(true));
        log.info("[HTTP 回放] 显式 v2 复现 Z@350 判定为高风险（阈值 0.2）");

        // 单点查询显式指定版本：与历史一致且不受 v2 影响
        mockMvc.perform(get("/api/risk/Z").param("asOf", "350").param("ruleVersion", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleVersion").value(1))
                .andExpect(jsonPath("$.risky").value(false));
        // 不指定版本：t=350 仍按时间点语义走 v1；t=600 走 v2
        mockMvc.perform(get("/api/risk/Z").param("asOf", "350"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleVersion").value(1));
        mockMvc.perform(get("/api/risk/Z").param("asOf", "600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleVersion").value(2))
                .andExpect(jsonPath("$.risky").value(true));
        log.info("[HTTP 单点] 显式 v1 复现与时间点语义均正确");
    }
}
