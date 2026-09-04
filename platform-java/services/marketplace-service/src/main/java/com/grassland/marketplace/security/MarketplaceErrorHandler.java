package com.grassland.marketplace.security;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * marketplace 全局错误处理（草场 Epic 4 Slice 4B）。首个 {@code @RestControllerAdvice}：从各
 * controller 抽出 重复的 {@code @ExceptionHandler}（DRY）。统一 legacy 兼容信封
 * {@code {success:false,error}}。
 *
 * <ul>
 * <li>{@link MarketplaceException} → 自带 HTTP 状态码（401/403/404/409…）。</li>
 * <li>{@link IllegalArgumentException} → 400（请求体校验失败，如缺
 * organizationId/title、maxSlots&lt;1）。</li>
 * <li>{@link DataIntegrityViolationException} 且命中 V52 唯一索引 → 409「该套餐已有进行中的推广任务」
 * （任务书 #75：并发双建的兜底——服务层 count 校验与 INSERT 之间的窗口由索引封死，这里翻成友好文案）。</li>
 * </ul>
 *
 * <p>
 * 必须返回 {@link ResponseEntity} 以设 HTTP 状态码（裸 Map 会被当作 200）。
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

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<Map<String, Object>> handleIntegrity(DataIntegrityViolationException error) {
		if (String.valueOf(error.getMessage()).contains("uniq_active_promotion_per_package")) {
			return ResponseEntity.status(409).body(Map.of("success", false, "error", "该套餐已有进行中的推广任务"));
		}
		return ResponseEntity.status(409).body(Map.of("success", false, "error", "数据约束冲突，请刷新后重试"));
	}
}
