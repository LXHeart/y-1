package com.grassland.intelligence.creationstudio.render;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 API101-20（C101-18）：GET /api/creation-studio/exports/{id}。 ready 返回
 * StudioExportResult（url 为读取时恢复的短时签名）；building/failed 返回
 * {exportId,state,error}。
 */
@RestController
public class CreationExportController {

	private final IntelligenceCallerResolver callers;
	private final CreationExportService exports;

	public CreationExportController(IntelligenceCallerResolver callers, CreationExportService exports) {
		this.callers = callers;
		this.exports = exports;
	}

	@GetMapping("/api/creation-studio/exports/{id}")
	public Mono<Map<String, Object>> load(@PathVariable String id, ServerWebExchange exchange) {
		UUID exportId;
		try {
			exportId = UUID.fromString(id);
		} catch (Exception error) {
			throw new IntelligenceException(404, "STUDIO_NOT_FOUND", "导出不存在");
		}
		return callers.requireUser(exchange.getRequest()).flatMap(caller -> exports.load(caller, exportId))
				.map(data -> Map.of("success", true, "data", data));
	}
}
