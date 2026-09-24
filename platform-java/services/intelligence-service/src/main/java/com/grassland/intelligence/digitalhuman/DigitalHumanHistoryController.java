package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanHistoryService.HistoryFilter;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.Page;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.SessionState;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 历史端点（任务书 #105G C105G-01 / K03 API09、API38）。Controller 只装配与解析。
 *
 * <p>
 * API09：GET /sessions 列表——过滤器/时间窗/游标在 service 校验（游标绑定 owner）。API38：DELETE
 * /sessions/{id} 带 JSON requestId（fetch 形态，不用 204）；202 回
 * Operation（pending/running=清理进行中， succeeded=收口完成；失败不谎报成功）。所有响应 no-store。
 */
@RestController
public class DigitalHumanHistoryController {

	private final DigitalHumanAuthorization authorization;
	private final DigitalHumanHistoryService history;

	public DigitalHumanHistoryController(DigitalHumanAuthorization authorization, DigitalHumanHistoryService history) {
		this.authorization = authorization;
		this.history = history;
	}

	// API09
	@GetMapping("/api/digital-human/sessions")
	public Mono<ResponseEntity<Map<String, Object>>> list(@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit, @RequestParam(required = false) String profileId,
			@RequestParam(required = false) String state, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to, ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest())
				.flatMap(actor -> history.list(actor, parseFilter(profileId, state, from, to), cursor, limit))
				.map(page -> envelope(page));
	}

	// API38
	@DeleteMapping("/api/digital-human/sessions/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> delete(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			DeleteRequest request = DigitalHumanOperations.parseStrict(body, DeleteRequest.class);
			if (request.requestId() == null) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "requestId 必填。"));
			}
			return history.delete(actor, id, request.requestId());
		}).map(operation -> ResponseEntity.accepted().cacheControl(CacheControl.noStore())
				.body(Map.of("success", true, "data", operation)));
	}

	private static HistoryFilter parseFilter(String profileId, String state, String from, String to) {
		UUID profile = null;
		if (profileId != null && !profileId.isBlank()) {
			try {
				profile = UUID.fromString(profileId.trim());
			} catch (IllegalArgumentException invalid) {
				throw new IntelligenceException(422, "dh_invalid_input", "profileId 格式不正确。");
			}
		}
		SessionState stateFilter = null;
		if (state != null && !state.isBlank()) {
			try {
				stateFilter = SessionState.valueOf(state.trim());
			} catch (IllegalArgumentException invalid) {
				throw new IntelligenceException(422, "dh_invalid_input", "state 不是有效的会话状态。");
			}
		}
		Instant fromAt = parseInstant("from", from);
		Instant toAt = parseInstant("to", to);
		// HistoryFilter 构造器校验 [from,to) 半开与 ≤90 天跨度（不合法 → 422）。
		return new HistoryFilter(profile, stateFilter, fromAt, toAt);
	}

	private static Instant parseInstant(String field, String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(value.trim());
		} catch (RuntimeException invalid) {
			throw new IntelligenceException(422, "dh_invalid_input", field + " 必须是 UTC RFC3339 时间。");
		}
	}

	private static ResponseEntity<Map<String, Object>> envelope(Object data) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("success", true, "data", data));
	}

	public record DeleteRequest(UUID requestId) {
	}
}
