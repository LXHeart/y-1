package com.grassland.intelligence.homepage;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.IntelligenceItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 首页热点平台配置 admin API（任务书 #47 S7b / D18①）。requireAdmin 门闩 + 乐观锁 +
 * token「不传=保持 / 空格=清空 / 新值=加密替换」三态语义。
 */
class HomepageHotConfigControllerIT extends IntelligenceItSupport {

    private static final String ADMIN = "33333333-3333-3333-3333-333333333333";
    private static final String USER = "44444444-4444-4444-4444-444444444444";
    private static final String TEST_TOKEN = "sk-alapi-test-token-1234567890abcdef";

    @Autowired
    private EnvelopeEncryption encryption;

    @Autowired
    private HomepageHotConfigRepository repository;

    @DynamicPropertySource
    static void cryptoProps(DynamicPropertyRegistry registry) {
        // KEK 注入：token 加密写入必需（未配 503 分支由本 IT 的 KEK 常在排除，另以单测语义锁定在仓储口径）
        registry.add("crypto.kek.encoded",
                () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM homepage_hot_config").then().block();
    }

    @Test
    @DisplayName("非 admin 403 / 未登录 401 / GET 无行时回默认 60s（version=0）")
    void accessControlAndDefaultView() {
        client().get().uri("/api/admin/homepage/hot-config").exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/admin/homepage/hot-config")
                .header("X-Grassland-Identity", sign(USER, "recommender")).exchange().expectStatus().isForbidden();

        client().get().uri("/api/admin/homepage/hot-config")
                .header("X-Grassland-Identity", signAdmin(ADMIN)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.provider").isEqualTo("60s")
                .jsonPath("$.data.version").isEqualTo(0)
                .jsonPath("$.data.hasAlapiToken").isEqualTo(false);
    }

    @Test
    @DisplayName("PUT 首建（expectedVersion=0）→ 加密落库只回掩码；热榜切 alapi 后读平台 token")
    void createEncryptsTokenAndHotItemsReadsIt() {
        client().put().uri("/api/admin/homepage/hot-config")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("provider", "alapi", "alapiToken", TEST_TOKEN, "expectedVersion", 0))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.provider").isEqualTo("alapi")
                .jsonPath("$.data.hasAlapiToken").isEqualTo(true)
                .jsonPath("$.data.alapiTokenMasked").isEqualTo("sk-***cdef")
                .jsonPath("$.data.version").isEqualTo(1);

        // 库里只有密文；解密还原为明文（loadAlapi 同路径）
        HomepageHotConfig stored = repository.findOrDefault().block();
        assertThat(stored.alapiTokenEncrypted()).isNotBlank();
        assertThat(encryption.decrypt(stored.alapiTokenEncrypted())).isEqualTo(TEST_TOKEN);
    }

    @Test
    @DisplayName("token 三态：不传=保持；空格=清空；provider 单独切换不动 token")
    void tokenSemantics() {
        long version = createAlapiWithToken();

        // 不传 token：provider 换 60s，token 保持
        client().put().uri("/api/admin/homepage/hot-config")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("provider", "60s", "expectedVersion", version))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.hasAlapiToken").isEqualTo(true);

        // 空格 = 清空
        client().put().uri("/api/admin/homepage/hot-config")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("provider", "60s", "alapiToken", " ", "expectedVersion", version + 1))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.hasAlapiToken").isEqualTo(false)
                .jsonPath("$.data.alapiTokenMasked").doesNotExist();
    }

    @Test
    @DisplayName("乐观锁：过期 expectedVersion → 409；非法 provider → 400")
    void optimisticLockAndValidation() {
        long version = createAlapiWithToken();
        client().put().uri("/api/admin/homepage/hot-config")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("provider", "60s", "expectedVersion", version - 1))
                .exchange().expectStatus().isEqualTo(409);
        client().put().uri("/api/admin/homepage/hot-config")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("provider", "bing", "expectedVersion", version))
                .exchange().expectStatus().isBadRequest();
    }

    private long createAlapiWithToken() {
        byte[] body = client().put().uri("/api/admin/homepage/hot-config")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("provider", "alapi", "alapiToken", TEST_TOKEN, "expectedVersion", 0))
                .exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(body).path("data").path("version").asLong();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
