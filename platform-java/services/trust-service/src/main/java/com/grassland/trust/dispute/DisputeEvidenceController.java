package com.grassland.trust.dispute;

import com.grassland.trust.adjudication.CaseEvidenceRedactor;
import com.grassland.trust.adjudication.RedactedEvidence;
import com.grassland.trust.security.DisputeAudience;
import com.grassland.trust.security.TrustCallerResolver;
import com.grassland.trust.security.TrustException;
import com.grassland.trust.workflow.AdjudicationSignaler;
import java.time.Duration;
import java.time.Instant;
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
 * 争议证据 HTTP 入口（GL-P2-TRUST-001 T1 + 任务书 #74 卡 B 质证期）。
 *
 * <ul>
 *   <li>POST /api/trust/disputes/{id}/evidence — 追加证据。卡 B 轮次规则（服务端强校验，409 人话文案）：
 *       phase=answer 仅被诉方且每案至多一次（须在质证期）；phase=rebuttal 仅原告、须已有 answer、每案至多一次；
 *       缺省 phase=claim 沿用既有「当事方/客服/服务断言可补证」语义。</li>
 *   <li>GET /api/trust/disputes/{id}/evidence — 列证据（脱敏，受众口径同审判快照 {@link DisputeAudience}）。</li>
 *   <li>POST /api/trust/disputes/{id}/evidence-done — 卡 B：当事方各自「质证完毕」（幂等）；双方齐 →
 *       signal workflow 提前开庭（窗口到点自动开庭是兜底）。</li>
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
    private final MarketplaceEngagementAuthorizationClient authorizer;
    private final AdjudicationSignaler signaler;

    public DisputeEvidenceController(TrustCallerResolver callers, DisputeCaseRepository disputes,
                                     DisputeEvidenceService evidenceService, DisputeEvidenceRepository evidenceRepo,
                                     CaseEvidenceRedactor redactor, DisputeAudience audience,
                                     MarketplaceEngagementAuthorizationClient authorizer,
                                     AdjudicationSignaler signaler) {
        this.callers = callers;
        this.disputes = disputes;
        this.evidenceService = evidenceService;
        this.evidenceRepo = evidenceRepo;
        this.redactor = redactor;
        this.audience = audience;
        this.authorizer = authorizer;
        this.signaler = signaler;
    }

    @PostMapping("/api/trust/disputes/{id}/evidence")
    public Mono<ResponseEntity<Map<String, Object>>> submit(@PathVariable String id,
                                                            @RequestBody SubmitEvidenceRequest body,
                                                            ServerHttpRequest request) {
        List<OpenDisputeRequest.EvidenceItem> items = body == null || body.items() == null ? List.of() : body.items();
        if (items.isEmpty()) {
            return fail(400, "至少提交一条证据");
        }
        String phase = body.phase() == null || body.phase().isBlank() ? "claim" : body.phase().trim();
        if (!List.of("claim", "answer", "rebuttal").contains(phase)) {
            return fail(400, "phase 必须是 claim、answer 或 rebuttal");
        }
        // 校验在请求体反序列化后即时做（EvidenceItem.validate），非法 → 400（经 TrustErrorHandler handleBadInput）。
        for (OpenDisputeRequest.EvidenceItem item : items) {
            item.validate();
        }
        return callers.resolve(request)
                .flatMap(caller -> disputes.findById(id)
                        .switchIfEmpty(fail(404, "争议不存在"))
                        .flatMap(d -> authorizeSubmission(caller, d, phase))
                        .filter(d -> !"final".equals(d.status()))
                        .switchIfEmpty(fail(409, "争议已终局，不可追加证据"))
                        .flatMap(d -> enforceRoundRules(caller, d, phase))
                        .flatMap(d -> {
                            String role = caller.isService() ? "marketplace" : caller.activeIdentityType();
                            Mono<List<DisputeEvidence>> saved = evidenceService.submit(d.id(), caller.accountId(),
                                    role, items, phase);
                            // 被诉方 answer 落库后置位 respondent_answered（缺席标注反转；同事务由 submit 保证证据原子）。
                            if ("answer".equals(phase)) {
                                return saved.flatMap(list -> disputes.markRespondentAnswered(d.id()).thenReturn(list));
                            }
                            return saved;
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

    /**
     * 任务书 #74 卡 B：当事方各自「质证完毕」（幂等，落各自 done_at）。双方齐 → signal workflow 提前开庭；
     * 窗口到点由 workflow Timer 自动开庭兜底。merchant_rejection / cs_direct 不质证 → 409。
     */
    @PostMapping("/api/trust/disputes/{id}/evidence-done")
    public Mono<ResponseEntity<Map<String, Object>>> evidenceDone(@PathVariable String id,
                                                                  ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> disputes.findById(id)
                        .switchIfEmpty(fail(404, "争议不存在"))
                        .filter(DisputeCase::inEvidencePhase)
                        .switchIfEmpty(fail(409, "该争议当前不在举证质证期"))
                        .flatMap(d -> {
                            // 开启人=原告；对方=被告（authorizer 复验当事方身份，recommender 无 org 也能过）。
                            Mono<DisputeCase> party;
                            if (caller.accountId().equals(d.openedByAccountId()) || caller.isCustomerService()) {
                                party = Mono.just(d);
                            } else {
                                party = authorizer
                                        .authorize(d.engagementRef(), caller.accountId(), caller.activeIdentityType())
                                        .filter(auth -> auth.organizationId().equals(d.organizationId()))
                                        .switchIfEmpty(fail(403, "无权操作该争议"))
                                        .thenReturn(d);
                            }
                            return party.flatMap(ok -> {
                                boolean claimant = caller.accountId().equals(d.openedByAccountId());
                                String side = claimant ? "claimant" : "respondent";
                                return disputes.markEvidenceDone(d.id(), side)
                                        .switchIfEmpty(fail(409, "重复提交：你已标记质证完毕"))
                                        .flatMap(updated -> bothDone(updated)
                                                ? signaler.concludeEvidence(updated.id()).thenReturn(updated)
                                                : Mono.just(updated));
                            });
                        }))
                .map(this::evidenceDoneBody)
                .map(body -> ResponseEntity.ok(body));
    }

    /** 双方均已「质证完毕」→ 提前开庭。 */
    private static boolean bothDone(DisputeCase d) {
        return d.claimantDoneAt() != null && d.respondentDoneAt() != null;
    }

    private Map<String, Object> evidenceDoneBody(DisputeCase d) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("disputeId", d.id());
        data.put("claimantDoneAt", d.claimantDoneAt() == null ? null : d.claimantDoneAt().toString());
        data.put("respondentDoneAt", d.respondentDoneAt() == null ? null : d.respondentDoneAt().toString());
        data.put("bothDone", bothDone(d));
        return Map.of("success", true, "data", data);
    }

    /**
     * 提交鉴权（卡 B.3）：claim 缺省沿用既有口径（当事商家 org 自查/客服/服务断言）；
     * answer 仅被诉方、rebuttal 仅原告——被诉方/原告身份经 authorizer 复验（同开争议口径），角色由 openedByRole 推导。
     */
    private Mono<DisputeCase> authorizeSubmission(TrustCallerResolver.Caller caller, DisputeCase d, String phase) {
        if ("answer".equals(phase) || "rebuttal".equals(phase)) {
            if (!caller.isMerchant() && !caller.isRecommender()) {
                return fail(403, "仅争议当事方可提交答辩或补充证据");
            }
            // authorizer 复验当事方身份 + canonical org（非当事方 → empty → 403）。
            return authorizer
                    .authorize(d.engagementRef(), caller.accountId(), caller.activeIdentityType())
                    .switchIfEmpty(fail(403, "无权向该争议提交证据"))
                    .filter(auth -> auth.organizationId().equals(d.organizationId()))
                    .switchIfEmpty(fail(403, "无权向该争议提交证据"))
                    .thenReturn(d);
        }
        if (caller.isServicePrincipal(TrustCallerResolver.MARKETPLACE_SERVICE)) {
            return Mono.just(d);
        }
        if (caller.isCustomerService()) {
            return Mono.just(d);  // admin 是客服超集
        }
        if (caller.isMerchant() && d.organizationId() != null && d.organizationId().equals(caller.organizationId())) {
            return Mono.just(d);
        }
        return fail(403, "无权向该争议提交证据");
    }

    /** 卡 B.3 轮次规则（服务端强校验，409 人话文案）。 */
    private Mono<DisputeCase> enforceRoundRules(TrustCallerResolver.Caller caller, DisputeCase d, String phase) {
        boolean inEvidenceWindow = d.inEvidencePhase();
        if ("answer".equals(phase)) {
            if (!inEvidenceWindow) {
                return fail(409, "举证质证期已结束，无法再提交答辩");
            }
            boolean isRespondent = !caller.accountId().equals(d.openedByAccountId())
                    && (( "merchant".equals(d.openedByRole()) && caller.isRecommender())
                        || ("recommender".equals(d.openedByRole()) && caller.isMerchant()));
            if (!isRespondent) {
                return fail(409, "仅被诉方可在质证期提交答辩");
            }
            if (d.respondentAnswered()) {
                return fail(409, "答辩已提交，每案至多一次");
            }
            return Mono.just(d);
        }
        if ("rebuttal".equals(phase)) {
            if (!inEvidenceWindow) {
                return fail(409, "举证质证期已结束，无法再补充证据");
            }
            boolean isClaimant = caller.accountId().equals(d.openedByAccountId());
            if (!isClaimant) {
                return fail(409, "仅发起争议一方可补充质证");
            }
            if (!d.respondentAnswered()) {
                return fail(409, "对方尚未答辩，暂不可补充质证");
            }
            return evidenceRepo.countByDisputeAndPhase(d.id(), "rebuttal")
                    .flatMap(count -> count > 0
                            ? fail(409, "补充质证已提交，每案至多一次")
                            : Mono.just(d));
        }
        return Mono.just(d);
    }

    /** 提交证据的鉴权：当事商家（org 自查）/ 客服 / admin / marketplace 服务断言（可代任一方）。 */
    private Map<String, Object> listBody(List<RedactedEvidence> redacted) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", redacted.size());
        data.put("items", redacted);
        return Map.of("success", true, "data", data);
    }

    /** 追加证据请求体。复用 {@link OpenDisputeRequest.EvidenceItem} 的字段与校验；phase 支持质证轮次（卡 B）。 */
    public record SubmitEvidenceRequest(List<OpenDisputeRequest.EvidenceItem> items, String phase) {

        /** 既有单参调用方兼容（缺省 claim）。 */
        public SubmitEvidenceRequest(List<OpenDisputeRequest.EvidenceItem> items) {
            this(items, null);
        }
    }

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new TrustException(status, message));
    }
}
