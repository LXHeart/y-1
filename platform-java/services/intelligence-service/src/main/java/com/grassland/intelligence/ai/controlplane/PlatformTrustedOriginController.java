package com.grassland.intelligence.ai.controlplane;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 受信平台端点 admin API（任务书 #58 决策 B / S1.2）。
 *
 * <p>端点（全部 {@code requireAdmin}，与 {@code /api/admin/ai/models}、{@code /credentials} 同闸）：
 * <ul>
 *   <li>GET    /api/admin/ai/trusted-origins — 列表（含停用行）</li>
 *   <li>POST   /api/admin/ai/trusted-origins — 新增（origin 重复 → 409）</li>
 *   <li>PUT    /api/admin/ai/trusted-origins/{id} — 修订（乐观锁冲突 → 409）</li>
 *   <li>DELETE /api/admin/ai/trusted-origins/{id} — 删除</li>
 * </ul>
 *
 * <p>校验：{@code ProviderUrlGuard} 结构校验 + 必须 HTTPS（回环例外沿
 * {@code ai.platform-model.allow-insecure-loopback}）+ 剥 path 只留 origin。
 * 本表取代 env 白名单（原 qwen base-url 锚点与 trusted-*-origins 两变量），
 * 是平台模型 base-url SSRF 校验的唯一真相源。
 */
@RestController
@RequestMapping("/api/admin/ai/trusted-origins")
public class PlatformTrustedOriginController {

    private final IntelligenceCallerResolver callers;
    private final TrustedOriginService origins;
    private final boolean allowInsecureLoopback;

    public PlatformTrustedOriginController(
            IntelligenceCallerResolver callers,
            TrustedOriginService origins,
            @org.springframework.beans.factory.annotation.Value(
                    "${ai.platform-model.allow-insecure-loopback:false}") boolean allowInsecureLoopback) {
        this.callers = callers;
        this.origins = origins;
        this.allowInsecureLoopback = allowInsecureLoopback;
    }

    @GetMapping
    public Flux<PlatformTrustedOrigin> list(ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest()).flatMapMany(admin -> origins.listAll());
    }

    @PostMapping
    public Mono<ResponseEntity<PlatformTrustedOrigin>> create(
            @Valid @RequestBody CreateTrustedOriginRequest body, ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(admin -> origins.create(normalizeOrigin(body.origin()), body.label(), admin.accountId()))
                .map(saved -> ResponseEntity.status(201).body(saved));
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<PlatformTrustedOrigin>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTrustedOriginRequest body,
            ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(admin -> origins.update(
                        id, normalizeOrigin(body.origin()), body.label(), body.enabled(),
                        body.expectedVersion(), admin.accountId()))
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable UUID id, ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(admin -> origins.delete(id)
                        .flatMap(deleted -> deleted
                                ? Mono.just(ResponseEntity.noContent().<Void>build())
                                : Mono.error(new IntelligenceException(404, "未找到受信端点: " + id))));
    }

    /** URL → 归一 origin（剥 path、补缺省端口）+ HTTPS/回环闸。入参非法抛 IllegalArgumentException（全局 400）。 */
    private String normalizeOrigin(String raw) {
        URI uri = com.grassland.intelligence.ai.ProviderUrlGuard.validate(raw);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                && !(allowInsecureLoopback && "http".equalsIgnoreCase(uri.getScheme())
                        && isLoopback(uri.getHost()))) {
            throw new IllegalArgumentException("受信端点必须使用 HTTPS（回环地址需开启 allow-insecure-loopback）");
        }
        if (uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath())) {
            throw new IllegalArgumentException("受信端点只允许 scheme://host[:port]，不能携带路径");
        }
        // 存归一形态（补缺省端口）：与策略校验值同构，https://x.com 与 https://x.com:443 不重复入库
        return PlatformProviderPolicy.originOf(uri);
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    public record CreateTrustedOriginRequest(
            @NotBlank(message = "origin 必填") String origin,
            String label) {
    }

    public record UpdateTrustedOriginRequest(
            @NotBlank(message = "origin 必填") String origin,
            String label,
            boolean enabled,
            @NotNull(message = "expectedVersion 必填（乐观锁）") Integer expectedVersion) {
    }
}
