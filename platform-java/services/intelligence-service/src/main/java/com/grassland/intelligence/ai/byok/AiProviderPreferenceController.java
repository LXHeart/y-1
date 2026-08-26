package com.grassland.intelligence.ai.byok;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 个人 BYOK 开关 API（任务书 #47 S5；D11–D14）。
 *
 * <p>端点（self-scoped，只操作调用者自己的偏好）：
 * <ul>
 *   <li>GET /api/ai/preferences — 四个能力的开关全集，未配置的补默认（on / version 0）</li>
 *   <li>PUT /api/ai/preferences/{capability} — 设开关，{@code expectedVersion} 乐观锁，冲突 409</li>
 * </ul>
 *
 * <p>不做 admin 面：这是用户自己的偏好，没有「代客修改」的场景。无 KEK 门控——本端点不碰密钥，
 * 只记「要不要用」，故 KEK 未配时也应能正常关闭开关（否则用户在加密基建故障时连退路都没有）。
 */
@RestController
@RequestMapping("/api/ai/preferences")
public class AiProviderPreferenceController {

    /** 与 {@code CreateAiProviderKeyRequest} 的 capability 值集一致（前端四个开关）。 */
    private static final Set<String> CAPABILITIES =
            Set.of("text", "image", "image_generation", "video_generation");
    /** 响应顺序固定，便于前端稳定渲染。 */
    private static final List<String> ORDERED =
            List.of("text", "image", "image_generation", "video_generation");

    private final IntelligenceCallerResolver callers;
    private final AiProviderPreferenceRepository repository;

    public AiProviderPreferenceController(
            IntelligenceCallerResolver callers, AiProviderPreferenceRepository repository) {
        this.callers = callers;
        this.repository = repository;
    }

    /** 四个能力的开关全集；未显式配置的补 D14 默认（on，version 0）。 */
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> list(ServerWebExchange exchange) {
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> repository.findByAccount(caller.accountId())
                        .collectMap(AiProviderPreference::capability, preference -> preference)
                        .map(configured -> {
                            List<Map<String, Object>> items = ORDERED.stream()
                                    .map(capability -> toItem(configured.containsKey(capability)
                                            ? configured.get(capability)
                                            : AiProviderPreference.defaultFor(caller.accountId(), capability)))
                                    .toList();
                            Map<String, Object> data = new LinkedHashMap<>();
                            data.put("items", items);
                            return success(data);
                        }));
    }

    @PutMapping("/{capability}")
    public Mono<ResponseEntity<Map<String, Object>>> update(
            @PathVariable String capability,
            @RequestBody UpdatePreferenceRequest body,
            ServerWebExchange exchange) {
        if (!CAPABILITIES.contains(capability)) {
            return Mono.error(new IntelligenceException(400, "不支持的能力: " + capability));
        }
        if (body.useOwnKey() == null) {
            return Mono.error(new IntelligenceException(400, "useOwnKey 必填"));
        }
        if (body.expectedVersion() == null || body.expectedVersion() < 0) {
            return Mono.error(new IntelligenceException(400, "expectedVersion 必填且不能为负"));
        }
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> repository
                        .upsert(caller.accountId(), capability, body.useOwnKey(), body.expectedVersion())
                        .map(saved -> success(toItem(saved)))
                        .switchIfEmpty(Mono.error(new IntelligenceException(409,
                                "开关已被其他会话修改，请重新加载后再试"))));
    }

    private static Map<String, Object> toItem(AiProviderPreference preference) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("capability", preference.capability());
        item.put("useOwnKey", preference.useOwnKey());
        // configured=false 表示走 D14 默认，前端据此区分「未配置」与「显式设为 true」
        item.put("configured", preference.version() > 0);
        item.put("version", preference.version());
        item.put("updatedAt", preference.updatedAt());
        return item;
    }

    private static ResponseEntity<Map<String, Object>> success(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    /** {@code expectedVersion=0} 表示预期无行（首次设置）。 */
    public record UpdatePreferenceRequest(Boolean useOwnKey, Long expectedVersion) {
    }
}
