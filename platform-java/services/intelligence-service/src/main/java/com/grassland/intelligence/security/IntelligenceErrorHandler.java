package com.grassland.intelligence.security;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;

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
        return ResponseEntity.status(error.status()).body(body(error.getMessage(), error.code()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadInput(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(body(error.getMessage(), null));
    }

    @ExceptionHandler(DataBufferLimitException.class)
    public ResponseEntity<Map<String, Object>> handleBodyLimit(DataBufferLimitException error) {
        return ResponseEntity.badRequest().body(body("单张图片不能超过 5 MB", null));
    }

    @ExceptionHandler(UnsupportedMediaTypeStatusException.class)
    public ResponseEntity<Map<String, Object>> handleMediaType(UnsupportedMediaTypeStatusException error) {
        return ResponseEntity.badRequest().body(body("图片上传失败，请检查文件后重试", null));
    }

    private static Map<String, Object> body(String message, String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", message);
        if (code != null) {
            body.put("code", code);
        }
        return body;
    }
}
