package com.grassland.trust.dispute;

import com.grassland.trust.adjudication.CaseEvidenceRedactor;
import com.grassland.trust.adjudication.RedactedEvidence;
import com.grassland.trust.security.DisputeAudience;
import com.grassland.trust.security.TrustCallerResolver;
import com.grassland.trust.security.TrustException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 争议证据 HTTP 入口（GL-P2-TRUST-001 T1）。
 *
 * <ul>
 *   <li>POST /api/trust/disputes/{id}/evidence — 追加证据（当事商家 org 自查 / 客服 / admin / marketplace 服务断言）。
 *       开争议时的初始证据走 {@link DisputeController}（同一事务串联 create dispute + 证据）。</li>
 *   <li>GET /api/trust/disputes/{id}/evidence — 列证据（脱敏，受众口径同审判快照 {@link DisputeAudience}）。
 *       <b>始终脱敏</b>：raw 证据内容不回任何 HTTP 响应（D-10）。</li>
 * </ul>
 */
@RestController
public class DisputeEvidenceController {

    private final TrustCallerResolver callers;
    private final DisputeCaseRepository disputes;
    private final DisputeEvidenceService evidenceService;
    private final DisputeEvidenceRepository evidenceRepo;
    private final CaseEvidenceRedactor redactor;
    private final DisputeAudience audience;

    public DisputeEvidenceController(TrustCallerResolver callers, DisputeCaseRepository disputes,
                                     DisputeEvidenceService evidenceService, DisputeEvidenceRepository evidenceRepo,
                                     CaseEvidenceRedactor redactor, DisputeAudience audience) {
        this.callers = callers;
        this.disputes = disputes;
        this.evidenceService = evidenceService;
        this.evidenceRepo = evidenceRepo;
        this.redactor = redactor;
        this.audience = audience;
    }

    @PostMapping("/api/trust/disputes/{id}/evidence")
    public Mono<ResponseEntity<Map<String, Object>>> submit(@PathVariable String id,
                                                            @RequestBody SubmitEvidenceRequest body,
                                                            ServerHttpRequest request) {
        List<OpenDisputeRequest.EvidenceItem> items = body == null || body.items() == null ? List.of() : body.items();
        if (items.isEmpty()) {
            return fail(400, "至少提交一条证据");
        }
        // 校验在请求体反序列化后即时做（EvidenceItem.validate），非法 → 400（经 TrustErrorHandler handleBadInput）。
        for (OpenDisputeRequest.EvidenceItem item : items) {
            item.validate();
        }
        return callers.resolve(request)
                .flatMap(caller -> disputes.findById(id)
                        .switchIfEmpty(fail(404, "争议不存在"))
                        .filter(d -> canSubmitEvidence(caller, d))
                        .switchIfEmpty(fail(403, "无权向该争议提交证据"))
                        .filter(d -> !"final".equals(d.status()))
                        .switchIfEmpty(fail(409, "争议已终局，不可追加证据"))
                        .flatMap(d -> {
                            String role = caller.isService() ? "marketplace" : caller.activeIdentityType();
                            return evidenceService.submit(d.id(), caller.accountId(), role, items);
                        })
                        .map(saved -> ResponseEntity.status(HttpStatus.CREATED)
                                .body(Map.of("success", true, "data", Map.of("submitted", saved.size())))));
    }

    @GetMapping("/api/trust/disputes/{id}/evidence")
    public Mono<ResponseEntity<Map<String, Object>>> list(@PathVariable String id, ServerHttpRequest request) {
        return callers.resolvePartyOrService(request, TrustCallerResolver.MARKETPLACE_SERVICE)
                .flatMap(caller -> disputes.findById(id)
                        .switchIfEmpty(fail(404, "争议不存在"))
                        .filterWhen(d -> audience.canRead(caller, d))
                        .switchIfEmpty(fail(403, "无权查看该争议证据"))
                        .flatMap(d -> evidenceRepo.listByDispute(id).collectList()
                                .map(redactor::redact)
                                .map(this::listBody)
                                .map(body -> ResponseEntity.ok(body))));
    }

    /** 提交证据的鉴权：当事商家（org 自查）/ 客服 / admin / marketplace 服务断言（可代任一方）。 */
    private boolean canSubmitEvidence(TrustCallerResolver.Caller caller, DisputeCase d) {
        if (caller.isServicePrincipal(TrustCallerResolver.MARKETPLACE_SERVICE)) {
            return true;
        }
        if (caller.isCustomerService()) {
            return true;  // admin 是客服超集
        }
        return caller.isMerchant() && d.organizationId() != null && d.organizationId().equals(caller.organizationId());
    }

    private Map<String, Object> listBody(List<RedactedEvidence> redacted) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", redacted.size());
        data.put("items", redacted);
        return Map.of("success", true, "data", data);
    }

    /** 追加证据请求体。复用 {@link OpenDisputeRequest.EvidenceItem} 的字段与校验。 */
    public record SubmitEvidenceRequest(List<OpenDisputeRequest.EvidenceItem> items) {}

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new TrustException(status, message));
    }
}
