package com.grassland.intelligence.digitalhuman;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Optional;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 数字人开关策略（任务书 #105B C105B-02 / K02/K10）：默认关闭、生命周期拒绝。
 *
 * <p>
 * 开关唯一事实源是 {@code dh_catalog} singleton（K10：重启不能把开关默认成 true）；缺行即全关。
 * newSessionsAllowed 只挡「新建」入口——end/删除/结算收尾不经该门，不能被全域开关误阻断（K10）。 intelligence
 * 无全局 ObjectMapper bean：本类持服务内私有实例（既定约束）。
 */
@Component
public class DigitalHumanPolicy {

	/** 目录开关快照（K02 Catalog 顶层 flags；version=0 表示无配置行=默认关闭）。 */
	public record CatalogFlags(boolean enabled, boolean newSessionsAllowed, boolean recordingEnabled,
			boolean customAvatarEnabled, int version) {

		static final CatalogFlags DISABLED = new CatalogFlags(false, false, false, false, 0);
	}

	private static final ObjectMapper JSON = new ObjectMapper();

	private final DatabaseClient db;

	public DigitalHumanPolicy(DatabaseClient db) {
		this.db = db;
	}

	/** 目录快照：dh_catalog 缺行 → DISABLED（不推断、不默认开）。 */
	public Mono<CatalogFlags> effectiveCatalog() {
		return db.sql("SELECT version, config_json::text AS config FROM dh_catalog WHERE singleton_id = 1")
				.map((row, metadata) -> parse(row.get("version", Integer.class), row.get("config", String.class))).one()
				.map(Optional::of).defaultIfEmpty(Optional.<CatalogFlags>empty())
				.map(optional -> optional.orElse(CatalogFlags.DISABLED));
	}

	/**
	 * 新建入口门：enabled=false 或 newSessionsAllowed=false → 404
	 * {@code dh_feature_disabled} （功能关闭不透露资源状态，K01）。
	 */
	public Mono<CatalogFlags> requireNewSessionsAllowed() {
		return effectiveCatalog().flatMap(flags -> flags.enabled() && flags.newSessionsAllowed()
				? Mono.just(flags)
				: Mono.error(new IntelligenceException(404, "dh_feature_disabled", "数字人功能暂未开放。")));
	}

	private static CatalogFlags parse(Integer version, String configJson) {
		try {
			JsonNode config = JSON.readTree(configJson == null ? "{}" : configJson);
			return new CatalogFlags(config.path("enabled").asBoolean(false),
					config.path("newSessionsAllowed").asBoolean(false),
					config.path("recordingEnabled").asBoolean(false),
					config.path("customAvatarEnabled").asBoolean(false), version == null ? 1 : version);
		} catch (Exception invalid) {
			// 配置损坏视同关闭（fail-safe），不吞异常细节上屏。
			return CatalogFlags.DISABLED;
		}
	}
}
