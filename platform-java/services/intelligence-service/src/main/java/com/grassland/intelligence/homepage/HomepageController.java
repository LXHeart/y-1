package com.grassland.intelligence.homepage;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 首页热点 HTTP 入口（GL: homepage 迁移）。响应契约与 legacy 1:1（前端零改动）。
 *
 * <p><b>不强制登录</b>：登录用户按其 homepage settings 选 provider；未登录走平台默认（60s）。
 */
@RestController
public class HomepageController {

    private final IntelligenceCallerResolver callers;
    private final HomepageHotService hotService;

    public HomepageController(IntelligenceCallerResolver callers, HomepageHotService hotService) {
        this.callers = callers;
        this.hotService = hotService;
    }

    @GetMapping("/api/homepage/hot-items")
    public Mono<ResponseEntity<Map<String, Object>>> hotItems(ServerHttpRequest request) {
        return callers.resolveOptional(request)
                .map(IntelligenceCallerResolver.Caller::accountId)
                .defaultIfEmpty("")
                .flatMap(accountId -> hotService.loadHotItems(accountId.isBlank() ? null : accountId))
                .map(result -> ResponseEntity.ok(Map.of("success", true, "data", result)));
    }

    @ExceptionHandler(IntelligenceException.class)
    public ResponseEntity<Map<String, Object>> handleError(IntelligenceException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }
}
