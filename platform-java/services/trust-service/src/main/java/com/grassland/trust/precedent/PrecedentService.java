package com.grassland.trust.precedent;

import com.grassland.trust.adjudication.CaseEvidenceRedactor;
import com.grassland.trust.dispute.DisputeCase;
import com.grassland.trust.dispute.DisputeCaseRepository;
import com.grassland.trust.dispute.DisputeEvidence;
import com.grassland.trust.dispute.DisputeEvidenceRepository;
import com.grassland.trust.judge.JudgeRepository;
import com.grassland.trust.judge.VoteTally;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 脱敏判例生成（任务书 #74 卡 G，拍板 D5 + 派生 6）。
 *
 * <p><b>红线</b>：判例内容 = 结构化字段拼接（任务类型+平台+争议焦点+双方主张摘要+裁决+投票理由摘要），
 * v1 不接 AI 生成（AI 不参与争议终审）；只存摘要与计数，不复制证据本体；表无 org/account/金额列
 * ——脱敏由构造保证，不靠查询过滤。
 *
 * <p>生成时机：终局事务后调用（{@code publishFinalStatus} activity / 客服终审 / SLA 自动终局 /
 * 商家手动 decide）；UNIQUE(dispute_id) 幂等——retrial 多轮终局只一行，final_via=retrial。
 */
@Component
public class PrecedentService {

    private static final Logger log = LoggerFactory.getLogger(PrecedentService.class);

    private final DisputeCaseRepository disputes;
    private final DisputeEvidenceRepository evidenceRepo;
    private final JudgeRepository judges;
    private final PrecedentRepository precedents;
    private final CaseEvidenceRedactor redactor;

    public PrecedentService(DisputeCaseRepository disputes, DisputeEvidenceRepository evidenceRepo,
                            JudgeRepository judges, PrecedentRepository precedents,
                            CaseEvidenceRedactor redactor) {
        this.disputes = disputes;
        this.evidenceRepo = evidenceRepo;
        this.judges = judges;
        this.precedents = precedents;
        this.redactor = redactor;
    }

    /** 终局即判例入库；非 final / 已入库（UNIQUE 幂等）→ empty。失败由调用方兜底 WARN（不阻断终局主链）。 */
    public Mono<PrecedentCase> record(String disputeId) {
        return disputes.findById(disputeId)
                .filter(d -> "final".equals(d.status()))
                .flatMap(this::build)
                .flatMap(precedents::insert)
                .onErrorResume(e -> {
                    log.warn("precedent build failed disputeId={}", disputeId, e);
                    return Mono.empty();
                });
    }

    private Mono<PrecedentCase> build(DisputeCase d) {
        Mono<String> viaMono = finalVia(d);
        Mono<List<Map<String, Object>>> voteSummaryMono = voteSummary(d);
        Mono<List<String>> rationaleMono = rationaleDigest(d);
        Mono<String> claimsMono = claimsSummary(d);
        return Mono.zip(viaMono, voteSummaryMono, rationaleMono, claimsMono).map(tuple -> {
            String via = tuple.getT1();
            String voteSummary = toJson(tuple.getT2());
            String rationaleDigest = toArrayJson(tuple.getT3());
            String focus = buildFocus(d);
            return new PrecedentCase(UUID.randomUUID().toString(), d.id(), null, d.taskPlatform(), d.kind(),
                    focus, tuple.getT4(), effectiveDecision(d), via, voteSummary, rationaleDigest, Instant.now());
        });
    }

    /** 终局经由：appeal 落 retrial → retrial；客服终裁（final_decided_by）/cs_direct/merchant_rejection → cs；面板 → panel。 */
    private Mono<String> finalVia(DisputeCase d) {
        if ("cs_direct".equals(d.effectiveChannel()) || "merchant_rejection".equals(d.kind())) {
            return Mono.just("cs");
        }
        return disputes.appealFinalDecision(d.id())
                .map(decision -> "retrial".equals(decision) ? "retrial"
                        : d.finalDecidedBy() != null ? "cs" : "panel")
                .defaultIfEmpty(d.finalDecidedBy() != null ? "cs" : "panel");
    }

    /** 各轮投票分布 + 熟手席计数；cs_direct 无投票轮 → 空数组。 */
    private Mono<List<Map<String, Object>>> voteSummary(DisputeCase d) {
        if (d.round() <= 0) {
            return Mono.just(List.of());
        }
        List<Mono<Map<String, Object>>> rounds = new ArrayList<>();
        for (int r = 1; r <= d.round(); r++) {
            int round = r;
            rounds.add(judges.tallyVotes(d.id(), round).flatMap(tally -> judges
                    .countMatchedPanel(d.id(), round)
                    .map(matched -> roundSummary(tally, matched))));
        }
        return reactor.core.publisher.Flux.fromIterable(rounds).flatMap(m -> m).collectList();
    }

    private static Map<String, Object> roundSummary(VoteTally tally, int matched) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("forMerchant", tally.forMerchant());
        m.put("forRecommender", tally.forRecommender());
        m.put("abstain", tally.abstain());
        m.put("matchedPlatformCount", matched);
        return m;
    }

    /** 终局轮每票理由截 100 字数组（不含 judge 账号；弃权理由一并展示——弃权也是「实际投出」）。 */
    private Mono<List<String>> rationaleDigest(DisputeCase d) {
        int round = Math.max(1, d.round());
        return judges.listVoteRationales(d.id(), round)
                .map(rationale -> truncate(redactor.maskText(rationale), 100))
                .filter(s -> !s.isBlank())
                .collectList();
    }

    /** 双方主张摘要：原告首轮 claim 证据 caption + 被告 answer 证据 caption（脱敏元信息，各截 200 字，不取 raw）。 */
    private Mono<String> claimsSummary(DisputeCase d) {
        return evidenceRepo.listByDispute(d.id()).collectList().map(evidence -> {
            String claim = evidence.stream()
                    .filter(e -> e.phase() == null || "claim".equals(e.phase()))
                    .findFirst()
                    .map(e -> truncate(redactor.maskText(PrecedentService.captionOf(e)), 200))
                    .orElse(d.reason() == null ? "" : truncate(redactor.maskText(d.reason()), 200));
            String answer = evidence.stream()
                    .filter(e -> "answer".equals(e.phase()))
                    .findFirst()
                    .map(e -> truncate(redactor.maskText(PrecedentService.captionOf(e)), 200))
                    .orElse(null);
            if (answer == null || answer.isBlank()) {
                // 派生 D1：被告缺席仅标注，不判负——判例如实呈现「未答辩」。
                return claim + "；被诉方未在质证期答辩";
            }
            return claim + "；被诉方：" + answer;
        });
    }

    private static String captionOf(DisputeEvidence e) {
        if (e.caption() != null && !e.caption().isBlank()) {
            return e.caption();
        }
        // 无 caption 的截图/外链：用类型描述占位，不取 raw（D-10 红线）。
        return switch (e.kind() == null ? "" : e.kind()) {
            case "screenshot" -> "[截图凭证]";
            case "link" -> "[外部链接]";
            default -> "[文本凭证]";
        };
    }

    private String buildFocus(DisputeCase d) {
        String kind = "merchant_rejection".equals(d.kind()) ? "商家履约异议" : "履约争议";
        String platform = d.taskPlatform() == null ? "" : "（平台 " + d.taskPlatform() + "）";
        String reason = d.reason() == null ? "" : truncate(redactor.maskText(d.reason()), 80);
        return truncate(kind + platform + "：" + reason, 200);
    }

    private static String effectiveDecision(DisputeCase d) {
        return d.finalDecision() != null ? d.finalDecision() : d.decision();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 极简 JSON 序列化（字符串列表；无需引 Jackson——纯输出场景，注意转义）。 */
    private static String toArrayJson(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quote(items.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String toJson(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{');
            int j = 0;
            for (Map.Entry<String, Object> entry : rows.get(i).entrySet()) {
                if (j++ > 0) {
                    sb.append(',');
                }
                sb.append(quote(entry.getKey())).append(':').append(entry.getValue());
            }
            sb.append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String quote(String value) {
        String escaped = value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return '"' + escaped + '"';
    }
}
