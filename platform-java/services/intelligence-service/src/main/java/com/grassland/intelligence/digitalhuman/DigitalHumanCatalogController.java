package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 目录端点（任务书 #105B C105B-03 / K03 API01）。游客只得静态
 * {authenticated:false,enabled,description} （K13.1
 * PublicCatalog）；登录用户返回完整目录（enabled=false 时字段如实关闭，可展示关闭说明）。
 */
@RestController
public class DigitalHumanCatalogController {

	private final IntelligenceCallerResolver callers;
	private final DigitalHumanCatalogService catalog;

	public DigitalHumanCatalogController(IntelligenceCallerResolver callers, DigitalHumanCatalogService catalog) {
		this.callers = callers;
		this.catalog = catalog;
	}

	@GetMapping("/api/digital-human/catalog")
	public Mono<ResponseEntity<Map<String, Object>>> catalog(ServerWebExchange exchange) {
		// resolveOptional：游客不 401，登录但断言失效按游客口径（目录无敏感内容）。
		return callers.resolveOptional(exchange.getRequest())
				.flatMap(caller -> catalog.load().map(data -> ok(envelope(data))))
				.switchIfEmpty(catalog.publicCatalog().map(publicData -> ok(envelope(publicData))));
	}

	private static Map<String, Object> envelope(Object data) {
		return Map.of("success", true, "data", data);
	}

	private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}
}
