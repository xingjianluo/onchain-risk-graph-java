package com.github.xingjianluo.onchainriskgraphjava.controller;

import com.github.xingjianluo.onchainriskgraphjava.controller.dto.LabelRequest;
import com.github.xingjianluo.onchainriskgraphjava.controller.dto.ReplayRequest;
import com.github.xingjianluo.onchainriskgraphjava.controller.dto.RuleRequest;
import com.github.xingjianluo.onchainriskgraphjava.controller.dto.TransactionRequest;
import com.github.xingjianluo.onchainriskgraphjava.ledger.TransactionLedger;
import com.github.xingjianluo.onchainriskgraphjava.model.FundFlow;
import com.github.xingjianluo.onchainriskgraphjava.model.QueryOutcome;
import com.github.xingjianluo.onchainriskgraphjava.model.QueryRejectReason;
import com.github.xingjianluo.onchainriskgraphjava.model.QueryRejection;
import com.github.xingjianluo.onchainriskgraphjava.model.ReplayItem;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskConclusion;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskLabel;
import com.github.xingjianluo.onchainriskgraphjava.model.RuleDiffResult;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitResult;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitStatus;
import com.github.xingjianluo.onchainriskgraphjava.model.Transaction;
import com.github.xingjianluo.onchainriskgraphjava.service.FundFlowService;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskLabelRegistry;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskPropagationService;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskReplayService;
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
    private final RiskReplayService replayService;

    public RiskGraphController(TransactionLedger ledger,
                               RiskLabelRegistry labelRegistry,
                               RuleService ruleService,
                               RiskPropagationService propagationService,
                               FundFlowService fundFlowService,
                               RiskReplayService replayService) {
        this.ledger = ledger;
        this.labelRegistry = labelRegistry;
        this.ruleService = ruleService;
        this.propagationService = propagationService;
        this.fundFlowService = fundFlowService;
        this.replayService = replayService;
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

    /**
     * 单地址风险结论。可选 ruleVersion 显式指定已发布规则版本复现结论；
     * 不指定时保持原有时间点语义。边界问题返回可机读的拒绝原因。
     */
    @GetMapping("/risk/{address}")
    public ResponseEntity<?> risk(@PathVariable String address,
                                  @RequestParam long asOf,
                                  @RequestParam(required = false) Integer ruleVersion) {
        QueryOutcome<RiskConclusion> outcome =
                propagationService.evaluateOutcome(address, asOf, ruleVersion, null);
        return outcome.isOk() ? ResponseEntity.ok(outcome.payload()) : rejected(outcome.rejection());
    }

    @GetMapping("/risk")
    public List<RiskConclusion> riskAll(@RequestParam long asOf) {
        return propagationService.evaluateAll(asOf);
    }

    /** 规则版本对比：同一时间点两个已发布版本的判定差异 */
    @GetMapping("/risk/compare")
    public ResponseEntity<?> compare(@RequestParam long asOf,
                                     @RequestParam int baseVersion,
                                     @RequestParam int targetVersion) {
        QueryOutcome<RuleDiffResult> outcome = replayService.compare(asOf, baseVersion, targetVersion);
        return outcome.isOk() ? ResponseEntity.ok(outcome.payload()) : rejected(outcome.rejection());
    }

    /**
     * 批量历史回放：一组时间点一次回放，结果与逐点单查完全一致、顺序与请求一致。
     * 回放期间的新入账/新标记/新规则不会进入本批结果。
     */
    @PostMapping("/risk/replay")
    public ResponseEntity<?> replay(@RequestBody(required = false) ReplayRequest request) {
        if (request == null || request.asOfs() == null || request.asOfs().isEmpty()) {
            return rejected(new QueryRejection(QueryRejectReason.EMPTY_REPLAY_REQUEST,
                    "批量回放请求不完整：address 与 asOfs（非空）均必填"));
        }
        List<ReplayItem> items = replayService.replay(
                request.address(), request.asOfs(), request.ruleVersion());
        return ResponseEntity.ok(items);
    }

    @GetMapping("/funds/{address}")
    public ResponseEntity<?> funds(@PathVariable String address, @RequestParam long asOf) {
        QueryOutcome<FundFlow> outcome = fundFlowService.summarizeOutcome(address, asOf);
        return outcome.isOk() ? ResponseEntity.ok(outcome.payload()) : rejected(outcome.rejection());
    }

    /** 查询拒绝的 HTTP 映射：入参非法 400；状态相关（无生效规则/版本不存在/空回放）422 */
    private static ResponseEntity<QueryRejection> rejected(QueryRejection rejection) {
        int code = switch (rejection.reason()) {
            case INVALID_ADDRESS, INVALID_AS_OF, INVALID_RULE_VERSION -> 400;
            case UNKNOWN_RULE_VERSION, NO_EFFECTIVE_RULE, EMPTY_REPLAY_REQUEST -> 422;
        };
        return ResponseEntity.status(code).body(rejection);
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
