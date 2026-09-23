package com.github.xingjianluo.onchainriskgraphjava.config;

import com.github.xingjianluo.onchainriskgraphjava.model.RuleVersion;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitResult;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitStatus;
import com.github.xingjianluo.onchainriskgraphjava.service.RuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时按 application.properties 中的 risk.* 配置写入初始规则版本。
 * 配置非法或与时间线冲突时抛出异常终止启动——非法规则绝不静默生效。
 */
@Component
public class RuleBootstrap {

    private static final Logger log = LoggerFactory.getLogger(RuleBootstrap.class);

    private final RuleService ruleService;
    private final RiskProperties properties;

    public RuleBootstrap(RuleService ruleService, RiskProperties properties) {
        this.ruleService = ruleService;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void publishInitialRule() {
        if (ruleService.currentVersionNumber() > 0) {
            return;
        }
        SubmitResult<RuleVersion> result = ruleService.publish(
                properties.getMaxHops(),
                properties.getDecay(),
                properties.getRiskThreshold(),
                properties.getInitialEffectiveAt());
        if (result.status() != SubmitStatus.ACCEPTED) {
            throw new IllegalStateException("初始风险规则配置非法，启动失败: " + result.message());
        }
        log.info("初始风险规则已按配置写入: {}", result.payload());
    }
}
