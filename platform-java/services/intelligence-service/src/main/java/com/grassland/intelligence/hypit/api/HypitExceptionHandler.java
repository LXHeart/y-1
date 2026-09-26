package com.grassland.intelligence.hypit.api;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Hypit 域错误信封（任务书 #107-1 C107-03 / K03/K07）：仅作用于 hypit 包，不改变其它 controller 的
 * JSON 格式。未知异常脱敏为固定文案，不回传上游堆栈/密钥/路径。
 */
@RestControllerAdvice(basePackages = "com.grassland.intelligence.hypit")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HypitExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(HypitExceptionHandler.class);

	@ExceptionHandler(IntelligenceException.class)
	public ResponseEntity<Map<String, Object>> handle(IntelligenceException error) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("success", false);
		body.put("error", error.getMessage());
		if (error.code() != null) {
			body.put("code", error.code());
		}
		return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore()).body(body);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> handleUnexpected(Exception error) {
		// 消息脱敏但保留首帧定位（type@frame），否则未知异常无从排查。
		StackTraceElement[] frames = error.getStackTrace();
		StringBuilder where = new StringBuilder();
		for (int i = 0; i < Math.min(3, frames.length); i++) {
			where.append(i == 0 ? "" : " <- ").append(frames[i]);
		}
		log.warn("hypit unexpected error type={} at {} message-redacted", error.getClass().getName(),
				where.isEmpty() ? "unknown" : where);
		return ResponseEntity.status(500).cacheControl(CacheControl.noStore())
				.body(HypitDtos.failure("Hypit 服务暂时不可用，请稍后重试。", "hypit_backend_unavailable"));
	}
}
