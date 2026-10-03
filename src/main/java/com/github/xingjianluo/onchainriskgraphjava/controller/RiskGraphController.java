package com.github.xingjianluo.onchainriskgraphjava.controller;

import com.github.xingjianluo.onchainriskgraphjava.controller.dto.BatchReplayRequest;
import com.github.xingjianluo.onchainriskgraphjava.controller.dto.LabelRequest;
import com.github.xingjianluo.onchainriskgraphjava.controller.dto.RuleRequest;
import com.github.xingjianluo.onchainriskgraphjava.controller.dto.TransactionRequest;
import com.github.xingjianluo.onchainriskgraphjava.ledger.TransactionLedger;
import com.github.xingjianluo.onchainriskgraphjava.model.BatchReplayResult;
import com.github.xingjianluo.onchainriskgraphjava.model.FundFlow;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskConclusion;
import com.github.xingjianluo.onchainriskgraphjava.model.RiskLabel;
import com.github.xingjianluo.onchainriskgraphjava.model.RuleVersionDiff;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitResult;
import com.github.xingjianluo.onchainriskgraphjava.model.SubmitStatus;
import com.github.xingjianluo.onchainriskgraphjava.model.Transaction;
import com.github.xingjianluo.onchainriskgraphjava.query.QueryValidator;
import com.github.xingjianluo.onchainriskgraphjava.service.BatchReplayService;
import com.github.xingjianluo.onchainriskgraphjava.service.FundFlowService;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskLabelRegistry;
import com.github.xingjianluo.onchainriskgraphjava.service.RiskPropagationService;
import com.github.xingjianluo.onchainriskgraphjava.service.RuleReplayService;
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
    private final RuleReplayService ruleReplayService;
    private final BatchReplayService batchReplayService;

    public RiskGraphController(TransactionLedger ledger,
                               RiskLabelRegistry labelRegistry,
                               RuleService ruleService,
                               RiskPropagationService propagationService,
                               FundFlowService fundFlowService,
                               RuleReplayService ruleReplayService,
                               BatchReplayService batchReplayService) {
        this.ledger = ledger;
        this.labelRegistry = labelRegistry;
        this.ruleService = ruleService;
        this.propagationService = propagationService;
        this.fundFlowService = fundFlowService;
        this.ruleReplayService = ruleReplayService;
        this.batchReplayService = batchReplayService;
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
     * 单地址风险结论。
     * 不传 ruleVersion：按 asOf 自动选取当时生效版本（与旧接口完全一致）；
     * 传 ruleVersion：严格使用该已发布版本复现结论（历史不被后来的规则带偏）。
     */
    @GetMapping("/risk/{address}")
    public RiskConclusion risk(@PathVariable String address,
                               @RequestParam Long asOf,
                               @RequestParam(required = false) Integer ruleVersion) {
        String cleanAddress = QueryValidator.requireAddress(address);
        long validAsOf = QueryValidator.requireAsOf(asOf);
        if (ruleVersion == null) {
            return propagationService.evaluate(cleanAddress, validAsOf);
        }
        return ruleReplayService.evaluateWithVersion(cleanAddress, validAsOf,
                QueryValidator.requireVersion(ruleVersion));
    }

    /** 该时点全部地址风险结论（同样支持 ruleVersion 显式回放） */
    @GetMapping("/risk")
    public List<RiskConclusion> riskAll(@RequestParam Long asOf,
                                        @RequestParam(required = false) Integer ruleVersion) {
        long validAsOf = QueryValidator.requireAsOf(asOf);
        if (ruleVersion == null) {
            return propagationService.evaluateAll(validAsOf);
        }
        int version = QueryValidator.requireVersion(ruleVersion);
        return propagationService.evaluateAll(validAsOf, version);
    }

    /**
     * 同一时间点两个规则版本的判定差异：新升高风险 / 解除高风险 / 分值变化。
     * 可选 address 只对比单地址；不给则对比快照内全部地址。
     */
    @GetMapping("/risk/diff")
    public RuleVersionDiff riskDiff(@RequestParam Long asOf,
                                    @RequestParam Integer versionA,
                                    @RequestParam Integer versionB,
                                    @RequestParam(required = false) String address) {
        return ruleReplayService.compareVersions(asOf, versionA, versionB, address);
    }

    /** 批量历史回放：一组点位一次性回放，结果与逐个单点查询一致、顺序稳定 */
    @PostMapping("/risk/replay")
    public BatchReplayResult batchReplay(@RequestBody BatchReplayRequest request) {
        if (request == null) {
            return batchReplayService.replay(null);
        }
        return batchReplayService.replay(request.points());
    }

    @GetMapping("/funds/{address}")
    public FundFlow funds(@PathVariable String address, @RequestParam Long asOf) {
        String cleanAddress = QueryValidator.requireAddress(address);
        long validAsOf = QueryValidator.requireAsOf(asOf);
        return fundFlowService.summarize(cleanAddress, validAsOf);
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
