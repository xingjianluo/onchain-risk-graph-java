package com.github.xingjianluo.onchainriskgraphjava.controller;

import com.github.xingjianluo.onchainriskgraphjava.controller.dto.LabelRequest;
import com.github.xingjianluo.onchainriskgraphjava.controller.dto.RuleRequest;
import com.github.xingjianluo.onchainriskgraphjava.controller.dto.TransactionRequest;
import com.github.xingjianluo.onchainriskgraphjava.ledger.TransactionLedger;
import com.github.xingjianluo.onchainriskgraphjava.model.FundFlow;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskConclusion;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskLabel;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitResult;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitStatus;
import com.github.xingjianluo.onchainriskgraphjava.model.Transaction;
import com.github.xingjianluo.onchainriskgraphjava.service.FundFlowService;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskLabelRegistry;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskPropagationService;
import com.github.xingjianluo.onchainriskgraphjava.service.RuleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 本地交易图：入账 / 风险标记 / 规则发布 / 风险评估 / 资金追踪。
 * HTTP 语义：ACCEPTED/DUPLICATE -> 200；REJECTED_CONFLICT -> 409；REJECTED_INVALID -> 400。
 * 响应体始终携带可机读的 {@link SubmitStatus} 与可解释原因。
 */
@RestController
@RequestMapping("/api")
public class RiskGraphController {

    private final TransactionLedger ledger;
    private final RiskLabelRegistry labelRegistry;
    private final RuleService ruleService;
    private final RiskPropagationService propagationService;
    private final FundFlowService fundFlowService;

    public RiskGraphController(TransactionLedger ledger,
                               RiskLabelRegistry labelRegistry,
                               RuleService ruleService,
                               RiskPropagationService propagationService,
                               FundFlowService fundFlowService) {
        this.ledger = ledger;
        this.labelRegistry = labelRegistry;
        this.ruleService = ruleService;
        this.propagationService = propagationService;
        this.fundFlowService = fundFlowService;
    }

    @PostMapping("/transactions")
    public ResponseEntity<SubmitResult<?>> submitTransaction(@RequestBody TransactionRequest request) {
        if (request == null || request.amount() == null || request.visibleAt() == null) {
            return badRequest("请求体不完整：txId/fromAddress/toAddress/amount/visibleAt 均必填");
        }
        Transaction tx = new Transaction(request.txId(), request.fromAddress(),
                request.toAddress(), request.amount(), request.visibleAt());
        return toResponse(ledger.submit(tx));
    }

    @PostMapping("/labels")
    public ResponseEntity<SubmitResult<?>> label(@RequestBody LabelRequest request) {
        if (request == null || request.baseScore() == null || request.effectiveAt() == null) {
            return badRequest("请求体不完整：address/baseScore/effectiveAt 均必填");
        }
        RiskLabel label = new RiskLabel(request.address(), request.baseScore(), request.effectiveAt());
        return toResponse(labelRegistry.register(label));
    }

    @PostMapping("/rules")
    public ResponseEntity<SubmitResult<?>> publishRule(@RequestBody RuleRequest request) {
        if (request == null || request.maxHops() == null || request.decay() == null
                || request.riskThreshold() == null || request.effectiveAt() == null) {
            return badRequest("请求体不完整：maxHops/decay/riskThreshold/effectiveAt 均必填");
        }
        return toResponse(ruleService.publish(request.maxHops(), request.decay(),
                request.riskThreshold(), request.effectiveAt()));
    }

    @GetMapping("/risk/{address}")
    public RiskConclusion risk(@PathVariable String address, @RequestParam long asOf) {
        return propagationService.evaluate(address, asOf);
    }

    @GetMapping("/risk")
    public List<RiskConclusion> riskAll(@RequestParam long asOf) {
        return propagationService.evaluateAll(asOf);
    }

    @GetMapping("/funds/{address}")
    public FundFlow funds(@PathVariable String address, @RequestParam long asOf) {
        return fundFlowService.summarize(address, asOf);
    }

    private static ResponseEntity<SubmitResult<?>> badRequest(String message) {
        return ResponseEntity.badRequest()
                .body(new SubmitResult<>(SubmitStatus.REJECTED_INVALID, message, null));
    }

    @SuppressWarnings("rawtypes")
    private static ResponseEntity<SubmitResult<?>> toResponse(SubmitResult result) {
        return switch (result.status()) {
            case REJECTED_CONFLICT -> ResponseEntity.status(409).body(result);
            case REJECTED_INVALID -> ResponseEntity.badRequest().body(result);
            case ACCEPTED, DUPLICATE -> ResponseEntity.ok(result);
        };
    }
}
