package com.grassland.intelligence.digitalhuman;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Optional;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Permanent retirement policy. Stored catalog data remains available for historical reconciliation. */
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

	/** Reject new sessions even if an old database configuration still enables them. */
	public Mono<CatalogFlags> requireNewSessionsAllowed() {
		return Mono.error(new IntelligenceException(410, "dh_retired", "实时数字人已退役，请使用视频创作。"));
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
