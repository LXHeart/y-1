package com.grassland.intelligence.security;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * intelligence 全局错误处理（草场 intelligence Slice 1）。统一 legacy 兼容信封 {@code {success:false,error}}。
 * <ul>
 *   <li>{@link IntelligenceException} → 自带 HTTP 状态码（401/403…）。</li>
 *   <li>{@link IllegalArgumentException} → 400（请求体校验失败）。</li>
 * </ul>
 * 必须返回 {@link ResponseEntity} 以设 HTTP 状态码（裸 Map 会被当作 200）。
 */
@RestControllerAdvice
public class IntelligenceErrorHandler {

    @ExceptionHandler(IntelligenceException.class)
    public ResponseEntity<Map<String, Object>> handle(IntelligenceException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadInput(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", error.getMessage()));
    }
}
