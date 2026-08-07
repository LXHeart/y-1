package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 争议参与方授权（草场 Slice 12 安全收口）。trust 开争议前调此内部端点，由 marketplace 作为
 * engagement 参与方与 canonical task organization 的权威。
 *
 * <p>仅接受 {@code principal=trust} 的服务断言（终端用户不可调）。判定：application 须 accepted；
 * merchant 发起 → 须为 task owner；recommender 发起 → 须为 application recommender。
 * 成功回 canonical engagement/org/recommender 与 accept 时专属客服权益快照；这些值均来自 marketplace 持久事实，
 * 不取自信任方传入。
 *
 * <p>状态：200 授权；404 application 不存在；409 非 accepted；403 非当事方/角色不符；400 非法 UUID/DTO。
 */
@RestController
public class DisputeAuthorizationController {

    public static final String TRUST_SERVICE = "trust";

    private final MarketplaceCallerResolver callers;
    private final TaskApplicationRepository applications;
    private final TaskRepository tasks;

    public DisputeAuthorizationController(MarketplaceCallerResolver callers,
                                          TaskApplicationRepository applications,
                                          TaskRepository tasks) {
        this.callers = callers;
        this.applications = applications;
        this.tasks = tasks;
    }

    @PostMapping(value = "/internal/marketplace/engagements/{applicationId}/dispute-authorization",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> authorize(@PathVariable String applicationId,
                                                               @RequestBody DisputeAuthorizationRequest body,
                                                               ServerHttpRequest request) {
        return callers.requireServicePrincipal(request, TRUST_SERVICE)
                .flatMap(service -> applications.findById(applicationId)
                        .switchIfEmpty(Mono.error(new MarketplaceException(404, "履约不存在")))
                        .flatMap(app -> {
                            if (!"accepted".equals(app.status())) {
                                return Mono.error(new MarketplaceException(409, "该履约不可开争议"));
                            }
                            return tasks.findById(app.taskId())
                                    .switchIfEmpty(Mono.error(new MarketplaceException(404, "履约不存在")))
                                    .flatMap(task -> {
                                        if (!isParty(body, app, task)) {
                                            return Mono.<Map<String, Object>>error(
                                                    new MarketplaceException(403, "无权对该履约开争议"));
                                        }
                                        Map<String, Object> data = new LinkedHashMap<>();
                                        data.put("engagementRef", app.id());
                                        data.put("organizationId", task.organizationId());
                                        data.put("recommenderAccountId", app.recommenderAccountId());
                                        data.put("premiumSupportAtAccept",
                                                Boolean.TRUE.equals(app.premiumSupportAtAccept()));
                                        return Mono.just(data);
                                    });
                        })
                        .map(data -> ResponseEntity.ok(Map.of("success", true, "data", data))));
    }

    /** merchant 须为 task owner；recommender 须为 application recommender。 */
    private static boolean isParty(DisputeAuthorizationRequest body, TaskApplication app, Task task) {
        if ("merchant".equals(body.actorIdentity())) {
            return body.actorAccountId().equals(task.ownerAccountId());
        }
        return body.actorAccountId().equals(app.recommenderAccountId());
    }

    @ExceptionHandler(MarketplaceException.class)
    public ResponseEntity<Map<String, Object>> handleError(MarketplaceException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    /** 非法 UUID / 缺字段 / 非法 identity → 400（不让坏输入进 DB cast 或被当业务拒绝）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException error) {
        return ResponseEntity.status(400).body(Map.of("success", false, "error", "授权请求不合法"));
    }
}
