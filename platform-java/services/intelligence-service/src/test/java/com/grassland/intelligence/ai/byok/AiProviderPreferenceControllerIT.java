package com.grassland.intelligence.ai.byok;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 模型来源总开关契约（任务书 #78 卡 B，D3 单总开关取代 per-capability 碎片开关）。
 *
 * <p>
 * 主行约定：{@code ai_provider_preference} 行 {@code capability='*'}；无主行 =
 * platform（默认）。 per-capability PUT 端点下线（404），旧 items 仅作只读兼容展示。
 */
@DisplayName("AiProviderPreferenceController (模型来源总开关)")
class AiProviderPreferenceControllerIT extends IntelligenceItSupport {

	private static final String ACCOUNT = "66666666-6666-6666-6666-666666666666";
	private static final String OTHER = "77777777-7777-7777-7777-777777777777";

	@BeforeEach
	void clean() {
		db.sql("DELETE FROM ai_provider_preference").then().block();
	}

	@Test
	@DisplayName("未配置：modelSource=platform / masterVersion=0；读取不写行")
	void defaultsToPlatformWithoutMasterRow() {
		client().get().uri("/api/ai/preferences").header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.modelSource").isEqualTo("platform")
				.jsonPath("$.data.masterVersion").isEqualTo(0).jsonPath("$.data.items.length()").isEqualTo(4); // 旧
																												// items
																												// 兼容展示

		Long rows = db.sql("SELECT COUNT(*) AS n FROM ai_provider_preference")
				.map((row, meta) -> row.get("n", Long.class)).one().block();
		assertThat(rows).isZero(); // 读取不该写行
	}

	@Test
	@DisplayName("切 own：写主行 use_own_key=true，version=1；GET 回 own + masterVersion=1")
	void switchToOwnWritesMasterRow() {
		putModelSource("own", 0).expectStatus().isOk().expectBody().jsonPath("$.data.modelSource").isEqualTo("own")
				.jsonPath("$.data.masterVersion").isEqualTo(1);

		client().get().uri("/api/ai/preferences").header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
				.exchange().expectBody().jsonPath("$.data.modelSource").isEqualTo("own")
				.jsonPath("$.data.masterVersion").isEqualTo(1);

		Boolean own = db
				.sql("SELECT use_own_key FROM ai_provider_preference " + "WHERE account_id = :a AND capability = '*'")
				.bind("a", ACCOUNT).map((row, meta) -> row.get("use_own_key", Boolean.class)).one().block();
		assertThat(own).isTrue();
	}

	@Test
	@DisplayName("可逆：own ↔ platform 切换 version 递增")
	void togglingIsReversible() {
		putModelSource("own", 0).expectStatus().isOk();
		putModelSource("platform", 1).expectStatus().isOk().expectBody().jsonPath("$.data.modelSource")
				.isEqualTo("platform").jsonPath("$.data.masterVersion").isEqualTo(2);
	}

	@Test
	@DisplayName("乐观锁：过期 expectedVersion → 409")
	void staleVersionConflicts() {
		putModelSource("own", 0).expectStatus().isOk();
		putModelSource("platform", 0).expectStatus().isEqualTo(409); // 已是 version 1
		putModelSource("platform", 7).expectStatus().isEqualTo(409);
	}

	@Test
	@DisplayName("self-scoped：只影响调用者自己的总开关")
	void isSelfScoped() {
		putModelSource("own", 0).expectStatus().isOk();

		client().get().uri("/api/ai/preferences").header("X-Grassland-Identity", sign(OTHER, "recommender")).exchange()
				.expectBody().jsonPath("$.data.modelSource").isEqualTo("platform").jsonPath("$.data.masterVersion")
				.isEqualTo(0);
	}

	@Test
	@DisplayName("入参校验：非法 modelSource 400；缺字段 400；per-capability PUT 下线 404；缺断言 401")
	void validatesInput() {
		client().put().uri("/api/ai/preferences/model-source")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"modelSource\":\"hybrid\",\"expectedVersion\":0}").exchange().expectStatus()
				.isBadRequest();

		client().put().uri("/api/ai/preferences/model-source")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"modelSource\":\"own\"}").exchange().expectStatus().isBadRequest();

		client().put().uri("/api/ai/preferences/text").header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue("{\"useOwnKey\":false,\"expectedVersion\":0}")
				.exchange().expectStatus().isNotFound();

		client().get().uri("/api/ai/preferences").exchange().expectStatus().isUnauthorized();
	}

	private WebTestClient.ResponseSpec putModelSource(String modelSource, long expectedVersion) {
		return client().put().uri("/api/ai/preferences/model-source")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"modelSource\":\"%s\",\"expectedVersion\":%d}".formatted(modelSource, expectedVersion))
				.exchange();
	}
}
