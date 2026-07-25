package com.grassland.trust.security;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * trust 全局错误处理（草场 Epic 6 Slice 6A）。统一 legacy 兼容信封 {@code {success:false,error}}。
 * {@link TrustException} → 自带状态码；{@link IllegalArgumentException} → 400。
 */
@RestControllerAdvice
public class TrustErrorHandler {

    @ExceptionHandler(TrustException.class)
    public ResponseEntity<Map<String, Object>> handle(TrustException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadInput(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", error.getMessage()));
    }
}
