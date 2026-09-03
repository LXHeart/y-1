package com.grassland.intelligence.ai.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 受信平台端点 admin CRUD（任务书 #58 决策 B / S1.2）。
 *
 * <p>
 * 覆盖：V56 种子可见、CRUD 契约（重复 409 / 乐观锁 409 / 删除 404 / 删除引用闸 409）、 校验（HTTPS、剥
 * path）、模型/凭据保存的 origin 未命中 422 引导闭环，以及 <b>写后生效</b>（origin
 * 增删后不重启，下一次校验即按新表——缓存事件重拉与 {@link TrustedOriginService#refresh()}
 * 是同一条重拉路径，测试里同步调它消除异步时序）。
 */
@DisplayName("PlatformTrustedOriginController (admin CRUD + 写后生效)")
class PlatformTrustedOriginControllerIT extends IntelligenceItSupport {

	private static final String ADMIN = "37373737-3737-3737-3737-373737373737";
	private static final String SEED_QWEN = "https://dashscope.aliyuncs.com";
	private static final String SEED_OPENAI = "https://api.openai.com";

	@Autowired
	TrustedOriginService trustedOrigins;

	@BeforeEach
	void clean() {
		db.sql("DELETE FROM platform_model_config_history").then().block();
		db.sql("DELETE FROM platform_model_concurrency_slot").then().block();
		db.sql("DELETE FROM platform_model_config").then().block();
		db.sql("DELETE FROM platform_provider_credential").then().block();
		// 保留 V56 两行内置默认（种子只在建表时跑一次，测试不能把它们洗掉）；
		// 其余行清空并同步刷新策略缓存，保证每个用例从确定的基线出发。
		db.sql("DELETE FROM platform_trusted_origin WHERE origin NOT IN (:a, :b)").bind("a", SEED_QWEN)
				.bind("b", SEED_OPENAI).then().block();
		trustedOrigins.refresh().block();
	}

	@Test
	@DisplayName("V56 种子两行对治理台可见（内置默认可删可停）")
	void seedsAreListed() {
		client().get().uri("/api/admin/ai/trusted-origins").header("X-Grassland-Identity", signAdmin(ADMIN)).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(2)
				.jsonPath("$[?(@.origin == '" + SEED_QWEN + "')].label").isEqualTo("内置默认·Qwen/DashScope");
	}

	@Test
	@DisplayName("新增端点：合法 201；重复 409；HTTP 非回环 400；带路径 400")
	void createValidatesOriginShape() {
		client().post().uri("/api/admin/ai/trusted-origins").header("X-Grassland-Identity", signAdmin(ADMIN))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"origin\":\"https://api.minimaxi.com\",\"label\":\"MiniMax 图像\"}").exchange()
				.expectStatus().isCreated().expectBody().jsonPath("$.origin").isEqualTo("https://api.minimaxi.com:443");

		client().post().uri("/api/admin/ai/trusted-origins").header("X-Grassland-Identity", signAdmin(ADMIN))
				.contentType(MediaType.APPLICATION_JSON).bodyValue("{\"origin\":\"https://api.minimaxi.com\"}")
				.exchange().expectStatus().isEqualTo(409);

		client().post().uri("/api/admin/ai/trusted-origins").header("X-Grassland-Identity", signAdmin(ADMIN))
				.contentType(MediaType.APPLICATION_JSON).bodyValue("{\"origin\":\"http://insecure.example.com\"}")
				.exchange().expectStatus().isBadRequest();

		client().post().uri("/api/admin/ai/trusted-origins").header("X-Grassland-Identity", signAdmin(ADMIN))
				.contentType(MediaType.APPLICATION_JSON).bodyValue("{\"origin\":\"https://api.example.com/v1\"}")
				.exchange().expectStatus().isBadRequest();
	}

	@Test
	@DisplayName("修订：乐观锁冲突 409；正确版本 200 且 version+1；停用后模型/凭据保存即 422")
	void updateUsesOptimisticLockAndDisablingTakesEffectImmediately() {
		String id = createOrigin("https://update.example.com");

		client().put().uri("/api/admin/ai/trusted-origins/" + id).header("X-Grassland-Identity", signAdmin(ADMIN))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"origin\":\"https://update.example.com\",\"label\":\"改\",\"enabled\":true,"
						+ "\"expectedVersion\":99}")
				.exchange().expectStatus().isEqualTo(409);

		client().put().uri("/api/admin/ai/trusted-origins/" + id).header("X-Grassland-Identity", signAdmin(ADMIN))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"origin\":\"https://update.example.com\",\"label\":\"改\",\"enabled\":true,"
						+ "\"expectedVersion\":0}")
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.version").isEqualTo(1);

		// 停用后（写后失效 → 下一次校验按新表）：该 origin 的凭据保存立即 422
		client().put().uri("/api/admin/ai/trusted-origins/" + id).header("X-Grassland-Identity", signAdmin(ADMIN))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"origin\":\"https://update.example.com\",\"label\":\"停\",\"enabled\":false,"
						+ "\"expectedVersion\":1}")
				.exchange().expectStatus().isOk();
		trustedOrigins.refresh().block();
		assertThat(trustedOrigins.enabledOrigins()).doesNotContain("https://update.example.com:443");

		saveCredentialFor("https://update.example.com/v1").expectStatus().isEqualTo(422);
	}

	@Test
	@DisplayName("语义查重：与种子行无端口写法等价的新增/改写都 409（归一化比对，非仅 raw 唯一索引）")
	void semanticDuplicateWithSeedIsRejected() {
		// 种子行存的是无端口形态 https://dashscope.aliyuncs.com；表单提交会被归一化为 :443，
		// raw 唯一索引拦不住语义等价行——必须由服务层归一化比对拦截，否则两行并存、停一行另一行仍生效
		client().post().uri("/api/admin/ai/trusted-origins").header("X-Grassland-Identity", signAdmin(ADMIN))
				.contentType(MediaType.APPLICATION_JSON).bodyValue("{\"origin\":\"https://dashscope.aliyuncs.com\"}")
				.exchange().expectStatus().isEqualTo(409);

		// 把已有行改写成与另一行语义相同的 origin → 同样 409
		String id = createOrigin("https://semantic.example.com");
		client().put().uri("/api/admin/ai/trusted-origins/" + id).header("X-Grassland-Identity", signAdmin(ADMIN))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"origin\":\"https://api.openai.com:443\",\"label\":\"撞种子\",\"enabled\":true,"
						+ "\"expectedVersion\":0}")
				.exchange().expectStatus().isEqualTo(409);
	}

	@Test
	@DisplayName("删除：204 且列表收缩；再删 404")
	void deletesEndpoint() {
		String id = createOrigin("https://delete.example.com");

		client().delete().uri("/api/admin/ai/trusted-origins/" + id).header("X-Grassland-Identity", signAdmin(ADMIN))
				.exchange().expectStatus().isNoContent();
		client().delete().uri("/api/admin/ai/trusted-origins/" + id).header("X-Grassland-Identity", signAdmin(ADMIN))
				.exchange().expectStatus().isNotFound();
	}

	@Test
	@DisplayName("删除闸门：启用中模型引用该 origin → 409 点名引用方；停用模型后可删（合法下线路径）")
	void deleteBlockedWhileReferencedByEnabledModel() {
		String id = createOrigin("https://guarded.example");
		createModelAt("https://guarded.example/v1");

		// 启用中的 text/primary 指向该 origin → 删除被拒，错误文案点名引用方定位
		client().delete().uri("/api/admin/ai/trusted-origins/" + id).header("X-Grassland-Identity", signAdmin(ADMIN))
				.exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.error")
				.value(message -> assertThat(String.valueOf(message)).contains("text/qwen-plus").contains("先停用"));

		// 先停用引用模型 → origin 可删（base_url 带路径也按归一化 origin 匹配到引用）
		client().delete().uri("/api/admin/ai/models/text/primary").header("X-Grassland-Identity", signAdmin(ADMIN))
				.exchange().expectStatus().isNoContent();
		client().delete().uri("/api/admin/ai/trusted-origins/" + id).header("X-Grassland-Identity", signAdmin(ADMIN))
				.exchange().expectStatus().isNoContent();
	}

	@Test
	@DisplayName("治理台 UX 闭环：未加端点先配凭据 → 422 引导文案；加端点后（不重启）即 201")
	void modelSaveGuidesToAddOriginFirst() {
		saveCredentialFor("https://fresh.example/v1").expectStatus().isEqualTo(422).expectBody().jsonPath("$.error")
				.value(message -> assertThat(String.valueOf(message)).contains("受信")
						.contains("https://fresh.example:443"));

		createOrigin("https://fresh.example");
		trustedOrigins.refresh().block();

		// 写后生效：不重启，同一 origin 的凭据保存立即放行
		saveCredentialFor("https://fresh.example/v1").expectStatus().isCreated();
	}

	private String createOrigin(String origin) {
		byte[] body = client().post().uri("/api/admin/ai/trusted-origins")
				.header("X-Grassland-Identity", signAdmin(ADMIN)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"origin\":\"" + origin + "\",\"label\":\"测试\"}").exchange().expectStatus().isCreated()
				.expectBody(byte[].class).returnResult().getResponseBody();
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("id").asText();
		} catch (Exception error) {
			throw new IllegalStateException("受信端点创建响应不可解析", error);
		}
	}

	private void createModelAt(String baseUrl) {
		client().post().uri("/api/admin/ai/models").header("X-Grassland-Identity", signAdmin(ADMIN))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"capability\":\"text\",\"modelRole\":\"primary\","
						+ "\"provider\":\"openai-completions\",\"model\":\"qwen-plus\"," + "\"baseUrl\":\"" + baseUrl
						+ "\"}")
				.exchange().expectStatus().isCreated();
	}

	private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec saveCredentialFor(String baseUrl) {
		return client().post().uri("/api/admin/ai/credentials").header("X-Grassland-Identity", signAdmin(ADMIN))
				.contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"cred-" + baseUrl.hashCode()
						+ "\",\"provider\":\"openai-compatible\"," + "\"baseUrl\":\"" + baseUrl + "\"}")
				.exchange();
	}
}
