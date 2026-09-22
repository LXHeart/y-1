package com.grassland.intelligence.digitalhuman;

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
 * 数字人域错误处理（任务书 #105B C105B-02 / K01）：统一信封/错误码，脱敏。
 *
 * <p>
 * 仅作用于 {@code com.grassland.intelligence.digitalhuman} 包内 controller（高于全局
 * advice 的优先级）：
 * <ul>
 * <li>{@link IntelligenceException} → 既有 legacy 信封
 * {@code {success:false,error,code}}，正文 {@code Cache-Control: no-store}（K01：所有
 * dh 正文不缓存）；</li>
 * <li>未知异常 → 500 {@code dh_runtime_unavailable} 固定文案——原始 provider
 * body/SDP/key/路径 不拼上屏；日志只记异常类型与脱敏 id，不抄原异常链正文。</li>
 * </ul>
 */
@RestControllerAdvice(basePackages = "com.grassland.intelligence.digitalhuman")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DigitalHumanExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(DigitalHumanExceptionHandler.class);

	@ExceptionHandler(IntelligenceException.class)
	public ResponseEntity<Map<String, Object>> handle(IntelligenceException error) {
		return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore())
				.body(body(error.getMessage(), error.code()));
	}

	/** 兜底脱敏：不把上游异常/密钥/路径带回响应体。 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> handleUnexpected(Exception error) {
		log.warn("dh unexpected error type={} message-redacted", error.getClass().getName());
		return ResponseEntity.status(500).cacheControl(CacheControl.noStore())
				.body(body("数字人服务暂时不可用，请稍后重试。", "dh_runtime_unavailable"));
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
