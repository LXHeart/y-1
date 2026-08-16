package com.grassland.marketplace.taskcatalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.marketplace.security.IdentityStoreAuthorizationClient;
import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceException;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Authoritative task context handoff for intelligence creation snapshots.
 * The browser never calls this endpoint; only the intelligence service principal may do so.
 *
 * <p>任务书 #24：响应额外携带 {@code storeBranding}（门店品牌语气/必须强调/禁止表达等，
 * 从 identity 内部批量端点现取）——不落 marketplace 新表、不改 V27 冻结触发器；
 * 快照时点语义由 intelligence 首次创建即不可变保证，验证引擎读 snapshot 零改动。
 */
@RestController
public class InternalCreationContextController {

    public static final String INTELLIGENCE_SERVICE = "intelligence";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final MarketplaceCallerResolver callers;
    private final TaskApplicationRepository applications;
    private final TaskRepository tasks;
    private final IdentityStoreAuthorizationClient identityStores;
    private final ObjectMapper mapper = new ObjectMapper();

    public InternalCreationContextController(MarketplaceCallerResolver callers,
                                              TaskApplicationRepository applications,
                                              TaskRepository tasks,
                                              IdentityStoreAuthorizationClient identityStores) {
        this.callers = callers;
        this.applications = applications;
        this.tasks = tasks;
        this.identityStores = identityStores;
    }

    /** Return the accepted engagement snapshot after checking the caller supplied account. */
    @PostMapping(value = "/internal/marketplace/engagements/{applicationId}/creation-context",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> get(
            @PathVariable String applicationId,
            @RequestBody CreationContextRequest body,
            ServerHttpRequest request) {
        return callers.requireServicePrincipal(request, INTELLIGENCE_SERVICE)
                .flatMap(ignored -> applications.findById(applicationId)
                        .switchIfEmpty(Mono.error(new MarketplaceException(404, "履约不存在")))
                        .flatMap(application -> {
                            if (!"accepted".equals(application.status())) {
                                return Mono.error(new MarketplaceException(409, "该履约尚未接受"));
                            }
                            if (body == null || body.taskId() == null || body.recommenderAccountId() == null
                                    || !application.taskId().equals(body.taskId())
                                    || !application.recommenderAccountId().equals(body.recommenderAccountId())) {
                                return Mono.error(new MarketplaceException(403, "任务上下文参与方不匹配"));
                            }
                            return applications.findTaskContextSnapshot(application.id())
                                    .switchIfEmpty(Mono.error(new MarketplaceException(409, "任务上下文快照尚未生成")))
                                    .flatMap(json -> tasks.findById(application.taskId())
                                            .switchIfEmpty(Mono.error(new MarketplaceException(404, "任务不存在")))
                                            .flatMap(task -> response(json, task)));
                        }));
    }

    private Mono<ResponseEntity<Map<String, Object>>> response(String json, Task task) {
        Map<String, Object> context;
        try {
            context = mapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException error) {
            return Mono.error(new MarketplaceException(500, "任务上下文快照损坏"));
        }
        return storeBranding(task).map(block -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskContext", context);
            data.put("organizationId", task.organizationId());
            if (!block.isEmpty()) {
                data.put("storeBranding", block);
            }
            return ResponseEntity.ok(Map.of("success", true, "data", data));
        });
    }

    /**
     * 门店品牌块：组织级任务（无 storeId）或 identity 无资料/不可用时返回空 map（不带 storeBranding 键）。
     * enrichment 失败不阻断创作上下文下发。
     */
    private Mono<Map<String, Object>> storeBranding(Task task) {
        if (task.storeId() == null) {
            return Mono.just(Map.of());
        }
        return identityStores.publicProfiles(List.of(task.storeId()))
                .map(profiles -> {
                    if (profiles.isEmpty()) {
                        return Map.<String, Object>of();
                    }
                    Map<String, Object> block = TaskStoreEnrichment.brandingBlock(profiles.get(0));
                    return block == null ? Map.<String, Object>of() : block;
                })
                .onErrorResume(error -> Mono.just(Map.of()));
    }

    public record CreationContextRequest(String taskId, String recommenderAccountId) {}
}
