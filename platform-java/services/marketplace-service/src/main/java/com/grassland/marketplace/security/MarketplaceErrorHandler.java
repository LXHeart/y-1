package com.grassland.marketplace.security;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * marketplace 全局错误处理（草场 Epic 4 Slice 4B）。首个 {@code @RestControllerAdvice}：从各 controller 抽出
 * 重复的 {@code @ExceptionHandler}（DRY）。统一 legacy 兼容信封 {@code {success:false,error}}。
 *
 * <ul>
 *   <li>{@link MarketplaceException} → 自带 HTTP 状态码（401/403/404/409…）。</li>
 *   <li>{@link IllegalArgumentException} → 400（请求体校验失败，如缺 organizationId/title、maxSlots&lt;1）。</li>
 * </ul>
 *
 * <p>必须返回 {@link ResponseEntity} 以设 HTTP 状态码（裸 Map 会被当作 200）。
 */
@RestControllerAdvice
public class MarketplaceErrorHandler {

    @ExceptionHandler(MarketplaceException.class)
    public ResponseEntity<Map<String, Object>> handle(MarketplaceException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadInput(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", error.getMessage()));
    }
}
