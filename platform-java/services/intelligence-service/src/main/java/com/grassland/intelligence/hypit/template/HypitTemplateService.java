package com.grassland.intelligence.hypit.template;

import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 正式模板目录服务（任务书 #107-3 C107-20 / K06）：目录真相是
 * platform-hypit/templates/catalog.json（broker 侧文件），经 sidecar
 * templates.list/templates.detail 命令读取——Java 不复制第二份目录。 materialState 如实透传，不伪造
 * ready；catalog 命中但未 ready 的模板克隆被拒。
 */
@Service
public class HypitTemplateService {

	private final HypitSidecarClient sidecar;
	private final HypitProperties properties;

	public HypitTemplateService(HypitSidecarClient sidecar, HypitProperties properties) {
		this.sidecar = sidecar;
		this.properties = properties;
	}

	public Mono<List<Map<String, Object>>> list() {
		return dispatch("templates.list", Map.of(), "templates.list").map(result -> {
			Object raw = result.get("templates");
			List<Map<String, Object>> items = new ArrayList<>();
			if (raw instanceof List<?> list) {
				for (Object entry : list) {
					items.add(HypitJson.mapValue(entry));
				}
			}
			return List.copyOf(items);
		});
	}

	public Mono<Map<String, Object>> detail(String templateId) {
		return dispatch("templates.detail", Map.of("templateId", templateId), "templates.detail")
				.map(HypitJson::mapValue)
				.flatMap(detail -> detail.isEmpty()
						? Mono.error(new IntelligenceException(HttpStatus.NOT_FOUND.value(), "hypit_not_found",
								"模板不存在：" + templateId))
						: Mono.just(detail));
	}

	/** 模板克隆入口校验：catalog 命中且 materialState=ready。 */
	public Mono<Map<String, Object>> requireClonable(String templateId) {
		return detail(templateId).flatMap(detail -> "ready".equals(detail.get("materialState"))
				? Mono.just(detail)
				: Mono.error(new IntelligenceException(HttpStatus.UNPROCESSABLE_ENTITY.value(),
						"hypit_missing_material", "模板素材未就绪：" + templateId)));
	}

	private Mono<Map<String, Object>> dispatch(String kind, Map<String, Object> payload, String what) {
		if (!properties.enabled()) {
			return Mono.error(new IntelligenceException(HttpStatus.SERVICE_UNAVAILABLE.value(), "hypit_disabled",
					"Hypit 引擎未启用。"));
		}
		return sidecar.commandAsync("java-" + what + "-" + UUID.randomUUID(), kind, payload).flatMap(receipt -> {
			if ("failed".equals(receipt.state())) {
				String code = receipt.error() == null
						? "engine_error"
						: HypitJson.stringValue(receipt.error().get("code"), "engine_error");
				String message = receipt.error() == null
						? what + " failed"
						: HypitJson.stringValue(receipt.error().get("message"), what + " failed");
				return Mono.error(new IntelligenceException(
						"hypit_not_found".equals(code) ? HttpStatus.NOT_FOUND.value() : 503, code, message));
			}
			return Mono.just(HypitJson.mapValue(receipt.result()));
		});
	}

}
