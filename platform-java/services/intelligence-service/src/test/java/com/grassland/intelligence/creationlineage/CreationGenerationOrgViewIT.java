package com.grassland.intelligence.creationlineage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.creationlineage.CreationGeneration.Kind;
import com.grassland.intelligence.creationlineage.CreationGeneration.Mode;
import com.grassland.intelligence.creationlineage.CreationGeneration.Resolution;
import com.grassland.intelligence.security.IdentityOrgAuthorizationClient;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 组织级审计视图端到端（任务书 #44 登记）：组织 ADMIN 可按组织列成员创作产出（带
 * ownerAccountId）；非 ADMIN 与跨组织统一 404（不探测组织存在性）；kind 过滤与游标分页可用。
 */
class CreationGenerationOrgViewIT extends IntelligenceItSupport {

    private static final String ORG = "org-lineage-44";
    private static final String ADMIN = "admin-lineage-44";
    private static final String MEMBER_A = "52525252-5252-5252-5252-525252525252";
    private static final String MEMBER_B = "53535353-5353-5353-5353-535353535353";

    @MockitoBean
    IdentityOrgAuthorizationClient orgAuthorization;

    @Autowired
    private CreationGenerationRepository generations;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM creation_generation WHERE organization_id = :org")
                .bind("org", ORG).then().block();
    }

    @Test
    @DisplayName("ADMIN 可见组织成员产出（含 ownerAccountId）；kind 过滤生效")
    void adminListsOrganizationGenerationsWithOwner() {
        allow(ADMIN, ORG);
        UUID articleId = seed(MEMBER_A, Kind.ARTICLE, "文章正文 100 字");
        seed(MEMBER_B, Kind.COMEDY_SCRIPT, "喜剧脚本 200 字");

        client().get().uri("/api/creation-generations/organizations/" + ORG)
                .header("X-Grassland-Identity", sign(ADMIN, "merchant"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(2)
                .jsonPath("$.data.items[0].ownerAccountId").isEqualTo(MEMBER_B)
                .jsonPath("$.data.items[0].resultTitle").isNotEmpty()
                .jsonPath("$.data.items[0].promptText").doesNotExist();

        client().get().uri("/api/creation-generations/organizations/" + ORG + "?kind=article")
                .header("X-Grassland-Identity", sign(ADMIN, "merchant"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].id").isEqualTo(articleId.toString())
                .jsonPath("$.data.items[0].ownerAccountId").isEqualTo(MEMBER_A);
    }

    @Test
    @DisplayName("非 ADMIN / 未授权 / 跨组织统一 404；无断言 401")
    void nonAdminAndCrossOrgGetUniform404() {
        when(orgAuthorization.require(any(), any(), any()))
                .thenReturn(Mono.error(new com.grassland.intelligence.security.IntelligenceException(403, "无权限")));
        client().get().uri("/api/creation-generations/organizations/" + ORG)
                .header("X-Grassland-Identity", sign("member-x", "merchant"))
                .exchange().expectStatus().isNotFound();

        client().get().uri("/api/creation-generations/organizations/" + ORG)
                .exchange().expectStatus().isUnauthorized();
    }

    private void allow(String account, String organizationId) {
        when(orgAuthorization.require(account, organizationId, "admin")).thenReturn(Mono.empty());
    }

    private UUID seed(String owner, Kind kind, String resultTitle) {
        return generations.insert(new CreationGeneration(
                null, owner, ORG, kind, Mode.INDEPENDENT, null, null,
                Resolution.PLATFORM, "qwen", "qwen-plus", 1, null,
                "审计视图测试 prompt", Map.of("topic", "审计"), java.util.List.of(),
                Map.of("contentLength", 100), java.util.List.of(), null))
                .block().id();
    }
}
