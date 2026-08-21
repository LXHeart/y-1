package com.grassland.intelligence.ai.byok;

import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.security.IdentityOrgAuthorizationClient;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/** 组织 BYOK 回退策略（ADR-D17 / D-11）：默认不允许、乐观锁、member 404 隐藏。 */
@DisplayName("AiOrgByokPolicyController (组织回退策略)")
class AiOrgByokPolicyControllerIT extends IntelligenceItSupport {

    private static final String ORG = "org-policy-" + UUID.randomUUID();
    private static final String ADMIN = "admin-policy-" + UUID.randomUUID();
    private static final String MEMBER = "member-policy-" + UUID.randomUUID();

    @MockitoBean
    IdentityOrgAuthorizationClient orgAuthorization;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM ai_org_byok_policy WHERE organization_id = :org")
                .bind("org", ORG)
                .fetch().rowsUpdated().block();
        when(orgAuthorization.require(ADMIN, ORG, "admin")).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("未配置返回默认（不允许/version 0）；创建后 version 1；更新递增")
    void defaultThenCreateThenUpdate() {
        client().get().uri(path())
                .header("X-Grassland-Identity", sign(ADMIN, "merchant"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.configured").isEqualTo(false)
                .jsonPath("$.data.allowPlatformFallback").isEqualTo(false)
                .jsonPath("$.data.version").isEqualTo(0);

        put("{\"expectedVersion\":0,\"allowPlatformFallback\":true}")
                .jsonPath("$.data.configured").isEqualTo(true)
                .jsonPath("$.data.allowPlatformFallback").isEqualTo(true)
                .jsonPath("$.data.version").isEqualTo(1);

        put("{\"expectedVersion\":1,\"allowPlatformFallback\":false}")
                .jsonPath("$.data.allowPlatformFallback").isEqualTo(false)
                .jsonPath("$.data.version").isEqualTo(2);
    }

    @Test
    @DisplayName("旧 version 冲突 409；重复创建 409；member 404")
    void conflictAndMemberHidden() {
        put("{\"expectedVersion\":0,\"allowPlatformFallback\":true}")
                .jsonPath("$.data.version").isEqualTo(1);

        client().put().uri(path())
                .header("X-Grassland-Identity", sign(ADMIN, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"expectedVersion\":0,\"allowPlatformFallback\":false}")
                .exchange()
                .expectStatus().isEqualTo(409);

        client().put().uri(path())
                .header("X-Grassland-Identity", sign(ADMIN, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"expectedVersion\":0,\"allowPlatformFallback\":false}")
                .exchange()
                .expectStatus().isEqualTo(409);

        when(orgAuthorization.require(MEMBER, ORG, "admin"))
                .thenReturn(Mono.error(new IntelligenceException(403, "组织权限不足")));
        client().get().uri(path())
                .header("X-Grassland-Identity", sign(MEMBER, "merchant"))
                .exchange()
                .expectStatus().isNotFound();
    }

    private String path() {
        return "/api/ai/organizations/" + ORG + "/byok-policy";
    }

    private org.springframework.test.web.reactive.server.WebTestClient.BodyContentSpec put(String body) {
        return client().put().uri(path())
                .header("X-Grassland-Identity", sign(ADMIN, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody();
    }
}
