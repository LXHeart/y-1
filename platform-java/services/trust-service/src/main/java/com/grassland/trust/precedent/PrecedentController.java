package com.grassland.trust.precedent;

import com.grassland.trust.security.TrustCallerResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 脱敏判例库 HTTP 入口（任务书 #74 卡 G，拍板 D5）。
 *
 * <ul>
 *   <li>GET /api/trust/precedents — 判例列表（<b>登录即可读</b>，无 org 限定；page/pageSize 分页 +
 *       platform/task_type/kind filter；created_at 倒序）。<b>不提供按商家/推荐官检索</b>（防侧信道还原身份）。</li>
 *   <li>GET /api/trust/precedents/{id} — 判例详情。</li>
 * </ul>
 *
 * <p>判例不含 org/account/金额字段——构造性脱敏（见 {@link PrecedentCase}），响应即表行投影。
 */
@RestController
public class PrecedentController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final TrustCallerResolver callers;
    private final PrecedentRepository precedents;

    public PrecedentController(TrustCallerResolver callers, PrecedentRepository precedents) {
        this.callers = callers;
        this.precedents = precedents;
    }

    @GetMapping("/api/trust/precedents")
    public Mono<ResponseEntity<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int pageSize,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String kind,
            ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> {
                    int size = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
                    int index = Math.max(1, page);
                    return precedents.list(platform, kind, taskType, size + 1, (long) (index - 1) * size)
                            .collectList()
                            .flatMap(rows -> precedents.count(platform, kind, taskType).map(total -> {
                                boolean hasMore = rows.size() > size;
                                List<PrecedentCase> items = hasMore
                                        ? rows.subList(0, size)
                                        : rows;
                                Map<String, Object> data = new LinkedHashMap<>();
                                data.put("items", items.stream().map(PrecedentController::toBody).toList());
                                data.put("page", index);
                                data.put("pageSize", size);
                                data.put("total", total);
                                data.put("hasMore", hasMore);
                                return data;
                            }));
                })
                .map(data -> ResponseEntity.ok(Map.of("success", true, "data", data)));
    }

    @GetMapping("/api/trust/precedents/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> detail(@PathVariable String id, ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> precedents.findById(id)
                        .switchIfEmpty(Mono.error(new com.grassland.trust.security.TrustException(404, "判例不存在")))
                        .map(p -> ResponseEntity.ok(Map.of("success", true, "data", toBody(p)))));
    }

    /** 判例投影：直接取表字段（无身份/金额列）；voteSummary/rationaleDigest 保持 JSON 原文交前端解析。 */
    private static Map<String, Object> toBody(PrecedentCase p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("disputeId", p.disputeId());
        m.put("taskType", p.taskType());
        m.put("taskPlatform", p.taskPlatform());
        m.put("disputeKind", p.disputeKind());
        m.put("focus", p.focus());
        m.put("claimsSummary", p.claimsSummary());
        m.put("decision", p.decision());
        m.put("finalVia", p.finalVia());
        m.put("voteSummary", p.voteSummary());
        m.put("rationaleDigest", p.rationaleDigest());
        m.put("createdAt", p.createdAt() == null ? null : p.createdAt().toString());
        return m;
    }
}
