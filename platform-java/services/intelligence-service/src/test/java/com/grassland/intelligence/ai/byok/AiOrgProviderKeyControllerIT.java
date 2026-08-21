package com.grassland.intelligence.ai.byok;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.DnsPinningResolver;
import com.grassland.intelligence.security.IdentityOrgAuthorizationClient;
import com.grassland.intelligence.security.IntelligenceException;
import java.net.InetAddress;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 组织级 BYOK 密钥管理（ADR-D17）集成测试：admin/owner 全 CRUD、member 与跨组织 404 隐藏、 组织维唯一
 * 409、密钥永不回显（只回掩码）。KEK fail-closed 条件与个人版同款。
 */
@DisplayName("AiOrgProviderKeyController (组织级 BYOK)")
@Import(AiOrgProviderKeyControllerIT.DnsTestConfiguration.class)
class AiOrgProviderKeyControllerIT extends IntelligenceItSupport {

	/** 32 字节 KEK（0x00..0x1F）的 Base64。 */
	private static final String TEST_KEK_BASE64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

	private static final String ORG = "org-byok-" + UUID.randomUUID();
	private static final String OTHER_ORG = "org-byok-other-" + UUID.randomUUID();
	private static final String ADMIN = "admin-byok-" + UUID.randomUUID();
	private static final String MEMBER = "member-byok-" + UUID.randomUUID();
	private static final String API_KEY = "sk-org-test-real-key-1234567890abcdef";

	@MockitoBean
	IdentityOrgAuthorizationClient orgAuthorization;

	@DynamicPropertySource
	static void cryptoProps(DynamicPropertyRegistry registry) {
		registry.add("crypto.kek.encoded", () -> TEST_KEK_BASE64);
	}

	@BeforeEach
	void clean() {
		db.sql("DELETE FROM ai_provider_key WHERE organization_id IN (:org, :otherOrg)").bind("org", ORG)
				.bind("otherOrg", OTHER_ORG).fetch().rowsUpdated().block();
		allowAdmin();
	}

	private void allowAdmin() {
		when(orgAuthorization.require(ADMIN, ORG, "admin")).thenReturn(Mono.empty());
	}

	private String createOrgKey(String capability) {
		byte[] body = client().post().uri("/api/ai/organizations/" + ORG + "/keys")
				.header("X-Grassland-Identity", sign(ADMIN, "merchant")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("capability", capability, "provider", "openai-compatible", "baseUrl",
						"https://api.openai.com", "model", "gpt-4", "apiKey", API_KEY))
				.exchange().expectStatus().isCreated().expectBody().jsonPath("$.maskedHint")
				.value(hint -> assertThat(String.valueOf(hint)).startsWith("sk-")).jsonPath("$.encryptedKey")
				.doesNotExist().returnResult().getResponseBody();
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asText();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	@DisplayName("admin 全生命周期：创建→列表→更新配置→轮换→停用；组织密文与掩码永不回显")
	void adminFullLifecycle() {
		String id = createOrgKey("text");

		client().get().uri("/api/ai/organizations/" + ORG + "/keys")
				.header("X-Grassland-Identity", sign(ADMIN, "merchant")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.length()").isEqualTo(1).jsonPath("$[0].id").isEqualTo(id).jsonPath("$[0].organizationId")
				.isEqualTo(ORG).jsonPath("$[0].encryptedKey").doesNotExist();

		client().put().uri("/api/ai/organizations/" + ORG + "/keys/" + id)
				.header("X-Grassland-Identity", sign(ADMIN, "merchant")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("baseUrl", "https://api.openai.com", "model", "gpt-4o")).exchange().expectStatus()
				.isOk().expectBody().jsonPath("$.model").isEqualTo("gpt-4o");

		client().put().uri("/api/ai/organizations/" + ORG + "/keys/" + id + "/key")
				.header("X-Grassland-Identity", sign(ADMIN, "merchant")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("apiKey", "sk-org-test-rotated-9876543210fedcba")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.maskedHint")
				.value(hint -> assertThat(String.valueOf(hint)).startsWith("sk-"));

		client().delete().uri("/api/ai/organizations/" + ORG + "/keys/" + id)
				.header("X-Grassland-Identity", sign(ADMIN, "merchant")).exchange().expectStatus().isNoContent();

		// 软删：管理台仍可见该行（enabled=false），但密文与掩码规则不变
		client().get().uri("/api/ai/organizations/" + ORG + "/keys/" + id)
				.header("X-Grassland-Identity", sign(ADMIN, "merchant")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.enabled").isEqualTo(false).jsonPath("$.encryptedKey").doesNotExist();
	}

	@Test
	@DisplayName("member 与跨组织访问统一 404「组织不存在」；组织不存在亦 404")
	void nonAdminAndForeignOrgHidden() {
		when(orgAuthorization.require(MEMBER, ORG, "admin"))
				.thenReturn(Mono.error(new IntelligenceException(403, "组织权限不足")));
		client().get().uri("/api/ai/organizations/" + ORG + "/keys")
				.header("X-Grassland-Identity", sign(MEMBER, "merchant")).exchange().expectStatus().isNotFound();

		client().post().uri("/api/ai/organizations/" + ORG + "/keys")
				.header("X-Grassland-Identity", sign(MEMBER, "merchant")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("capability", "text", "provider", "openai-compatible", "baseUrl",
						"https://api.openai.com", "apiKey", API_KEY))
				.exchange().expectStatus().isNotFound();

		when(orgAuthorization.require(ADMIN, OTHER_ORG, "admin"))
				.thenReturn(Mono.error(new IntelligenceException(404, "组织不存在")));
		client().get().uri("/api/ai/organizations/" + OTHER_ORG + "/keys")
				.header("X-Grassland-Identity", sign(ADMIN, "merchant")).exchange().expectStatus().isNotFound();
	}

	@Test
	@DisplayName("同组织同能力第二把有效密钥 → 409（V41 组织维唯一索引）")
	void duplicateActiveOrgKeyRejected() {
		createOrgKey("text");
		client().post().uri("/api/ai/organizations/" + ORG + "/keys")
				.header("X-Grassland-Identity", sign(ADMIN, "merchant")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("capability", "text", "provider", "openai-compatible", "baseUrl",
						"https://api.openai.com", "apiKey", API_KEY))
				.exchange().expectStatus().isEqualTo(409);
	}

	@Test
	@DisplayName("组织密钥不落入个人作用域：个人列表/详情均不可见")
	void orgKeysInvisibleToPersonalEndpoints() {
		String id = createOrgKey("text");
		when(orgAuthorization.require(ADMIN, ORG, "admin")).thenReturn(Mono.empty());

		client().get().uri("/api/ai/keys").header("X-Grassland-Identity", sign(ADMIN, "merchant")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(0);

		client().get().uri("/api/ai/keys/" + id).header("X-Grassland-Identity", sign(ADMIN, "merchant")).exchange()
				.expectStatus().isNotFound();
	}

	@TestConfiguration
	static class DnsTestConfiguration {
		@Bean
		@Primary
		DnsPinningResolver deterministicDnsPinningResolver() {
			return DnsPinningResolver.create(host -> {
				try {
					return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
				} catch (java.net.UnknownHostException e) {
					throw new IllegalStateException(e);
				}
			});
		}
	}
}
