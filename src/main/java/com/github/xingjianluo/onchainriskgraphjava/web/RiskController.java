package com.github.xingjianluo.onchainriskgraphjava.web;

import com.github.xingjianluo.onchainriskgraphjava.domain.IngestResult;
import com.github.xingjianluo.onchainriskgraphjava.domain.RiskConclusion;
import com.github.xingjianluo.onchainriskgraphjava.domain.RiskLabel;
import com.github.xingjianluo.onchainriskgraphjava.domain.Transaction;
import com.github.xingjianluo.onchainriskgraphjava.rule.RiskRule;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskGraphService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * HTTP 入口。骨架：仅类型与空实现。
 */
@RestController
@RequestMapping("/api")
public class RiskController {

    private final RiskGraphService service;

    public RiskController(RiskGraphService service) {
        this.service = service;
    }

    @PostMapping("/transactions")
    public IngestResult ingest(@RequestBody Transaction tx) {
        return service.ingest(tx);
    }

    @PostMapping("/labels")
    public void addLabel(@RequestBody RiskLabel label) {
        service.addLabel(label);
    }

    @PostMapping("/rules")
    public RiskRule registerRule(@RequestBody RiskRule rule) {
        return service.registerRule(rule);
    }

    @GetMapping("/addresses/{address}/risk")
    public RiskConclusion risk(@PathVariable String address,
                               @RequestParam long asOf,
                               @RequestParam(required = false) String ruleVersion) {
        return service.riskOf(address, asOf, ruleVersion);
    }

    @GetMapping("/replay")
    public List<Transaction> replay(@RequestParam long asOf) {
        return service.replay(asOf);
    }
}
