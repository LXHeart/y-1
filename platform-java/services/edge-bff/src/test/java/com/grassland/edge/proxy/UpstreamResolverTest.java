package com.grassland.edge.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UpstreamResolverTest {
    private static final URI LEGACY = URI.create("http://legacy:3000");
    private static final URI IDENTITY = URI.create("http://identity:8082");
    private static final URI MARKETPLACE = URI.create("http://marketplace:8083");
    private static final URI FINANCE = URI.create("http://finance:8084");
    private static final URI TRUST = URI.create("http://trust:8085");
    private static final URI INTELLIGENCE = URI.create("http://intelligence:8086");

    private final EdgeRoutingProperties properties = new EdgeRoutingProperties(
        Map.of("legacy", LEGACY, "identity", IDENTITY, "marketplace", MARKETPLACE,
            "finance", FINANCE, "trust", TRUST, "intelligence", INTELLIGENCE),
        List.of(
            new RouteProperties("GET", "/api/auth/me", "identity", true),
            new RouteProperties(null, "/api/v2/**", "identity", true),
            // Slice 4C：/api/tasks** 全方法 → marketplace（无 method；前缀覆盖子路径）
            new RouteProperties(null, "/api/tasks", "marketplace", true),
            // P0-1：身份域非 auth 端点 + finance + trust 全量经 BFF
            new RouteProperties(null, "/api/organizations", "identity", true),
            new RouteProperties(null, "/api/me", "identity", true),
            new RouteProperties(null, "/api/admin/permission-requests", "identity", true),
            new RouteProperties(null, "/api/finance", "finance", true),
            new RouteProperties(null, "/api/trust", "trust", true),
            // 推荐官画像 → identity，声誉 → marketplace（两个不同上游，前缀不得互相抢占）
            new RouteProperties(null, "/api/recommenders", "identity", true),
            new RouteProperties(null, "/api/reputation", "marketplace", true),
            // intelligence Slice 1：/api/intelligence 前缀 → intelligence（冒烟端点 + 后续业务）
            new RouteProperties(null, "/api/intelligence", "intelligence", true),
            // intelligence Slice 2：/api/comedy-generation 前缀 → intelligence（脱口秀迁入，路径沿用 legacy）
            new RouteProperties(null, "/api/comedy-generation", "intelligence", true),
            // intelligence Slice 3：文章生成三文本端点 method+path 精确路由（图片端点与该前缀共享，仍走 legacy）
            new RouteProperties("POST", "/api/article-generation/titles", "intelligence", true),
            new RouteProperties("POST", "/api/article-generation/outline", "intelligence", true),
            new RouteProperties("POST", "/api/article-generation/content", "intelligence", true),
            // intelligence Slice 4：视频制作脚本精确切换；generate-video stub 仍 legacy
            new RouteProperties("POST", "/api/video-production/generate-script", "intelligence", true)),
        "legacy");

    private final UpstreamResolver resolver = new UpstreamResolver(properties);

    @Test
    void routesAuthMeToIdentity() {
        assertThat(resolver.resolve("GET", "/api/auth/me")).isEqualTo(IDENTITY);
    }

    @Test
    void routesLegacyByDefault() {
        assertThat(resolver.resolve("POST", "/api/auth/login")).isEqualTo(LEGACY);
        assertThat(resolver.resolve("GET", "/api/douyin/proxy/token")).isEqualTo(LEGACY);
        assertThat(resolver.resolve("GET", "/health")).isEqualTo(LEGACY);
    }

    @Test
    void methodSpecificity() {
        // POST /api/auth/me should NOT match the GET-only route -> legacy
        assertThat(resolver.resolve("POST", "/api/auth/me")).isEqualTo(LEGACY);
    }

    UpstreamResolver disabledRouteResolver() {
        EdgeRoutingProperties disabled = new EdgeRoutingProperties(
            Map.of("legacy", LEGACY, "identity", IDENTITY),
            List.of(new RouteProperties("GET", "/api/auth/me", "identity", false)),
            "legacy");
        return new UpstreamResolver(disabled);
    }

    @Test
    void disabledRouteFallsBackToLegacy() {
        assertThat(disabledRouteResolver().resolve("GET", "/api/auth/me")).isEqualTo(LEGACY);
    }

    @Test
    void prefixGlobMatches() {
        assertThat(resolver.resolve("GET", "/api/v2/anything")).isEqualTo(IDENTITY);
        assertThat(resolver.resolve("GET", "/api/v2")).isEqualTo(IDENTITY);
    }

    // ---------- Slice 4C: /api/tasks** → marketplace（内部上游，触发断言签发）----------

    @Test
    void routesTasksToMarketplaceAllMethodsAndSubPaths() {
        assertThat(resolver.resolve("POST", "/api/tasks")).isEqualTo(MARKETPLACE);
        assertThat(resolver.resolve("GET", "/api/tasks")).isEqualTo(MARKETPLACE);
        assertThat(resolver.resolve("GET", "/api/tasks/" + TASK_ID)).isEqualTo(MARKETPLACE);
        assertThat(resolver.resolve("POST", "/api/tasks/" + TASK_ID + "/applications")).isEqualTo(MARKETPLACE);
        assertThat(resolver.resolve("POST", "/api/tasks/" + TASK_ID + "/applications/" + APP_ID + "/accept"))
                .isEqualTo(MARKETPLACE);
    }

    @Test
    void tasksIsInternalUpstreamSoAssertionGetsSigned() {
        // isInternalUpstream=true → InternalAssertionFilter 签发 X-Grassland-Identity（HLD 7.4 端到端打通）
        assertThat(resolver.isInternalUpstream("POST", "/api/tasks")).isTrue();
        assertThat(resolver.isInternalUpstream("GET", "/api/tasks/" + TASK_ID + "/applications")).isTrue();
        // 非 task 路径仍是 legacy（内部判定 false → 不签断言）
        assertThat(resolver.isInternalUpstream("GET", "/api/douyin/proxy/token")).isFalse();
    }

    // ---------- P0-1: 身份域非 auth + finance + trust 经 BFF ----------

    @Test
    void routesIdentityDomainEndpoints() {
        assertThat(resolver.resolve("POST", "/api/organizations")).isEqualTo(IDENTITY);
        assertThat(resolver.resolve("GET", "/api/organizations/" + ORG_ID + "/stores")).isEqualTo(IDENTITY);
        assertThat(resolver.resolve("GET", "/api/organizations/" + ORG_ID + "/quota")).isEqualTo(IDENTITY);
        assertThat(resolver.resolve("GET", "/api/me/identities")).isEqualTo(IDENTITY);
        assertThat(resolver.resolve("POST", "/api/me/active-identity")).isEqualTo(IDENTITY);
        assertThat(resolver.resolve("DELETE", "/api/me/sessions/sid-x")).isEqualTo(IDENTITY);
    }

    @Test
    void routesFinanceAndTrust() {
        assertThat(resolver.resolve("POST", "/api/finance/accounts")).isEqualTo(FINANCE);
        assertThat(resolver.resolve("POST", "/api/finance/reservations/eng-1/release")).isEqualTo(FINANCE);
        assertThat(resolver.resolve("POST", "/api/trust/disputes")).isEqualTo(TRUST);
        assertThat(resolver.resolve("POST", "/api/trust/disputes/d-1/votes")).isEqualTo(TRUST);
    }

    @Test
    void adminPermissionRequestsGoesToIdentityButLegacyAdminStays() {
        // identity 的权限审核队列 → identity
        assertThat(resolver.resolve("GET", "/api/admin/permission-requests")).isEqualTo(IDENTITY);
        assertThat(resolver.resolve("POST", "/api/admin/permission-requests/req-1")).isEqualTo(IDENTITY);
        // ⚠️ 回归防护：legacy Express 的 /api/admin/users、/api/admin/adjust-credits 必须仍走 legacy。
        // 若路由误配为前缀 /api/admin，这两条会被抢走 → 旧后台功能挂掉。
        assertThat(resolver.resolve("GET", "/api/admin/users")).isEqualTo(LEGACY);
        assertThat(resolver.resolve("POST", "/api/admin/adjust-credits")).isEqualTo(LEGACY);
    }

    @Test
    void newRoutesAreInternalUpstreamsSoAssertionGetsSigned() {
        assertThat(resolver.isInternalUpstream("POST", "/api/organizations")).isTrue();
        assertThat(resolver.isInternalUpstream("GET", "/api/me/identities")).isTrue();
        assertThat(resolver.isInternalUpstream("POST", "/api/finance/accounts")).isTrue();
        assertThat(resolver.isInternalUpstream("POST", "/api/trust/disputes")).isTrue();
        assertThat(resolver.isInternalUpstream("GET", "/api/admin/permission-requests")).isTrue();
        // legacy admin 不是内部上游 → 不签断言（伪造头仍被 filter 剥离）
        assertThat(resolver.isInternalUpstream("GET", "/api/admin/users")).isFalse();
    }

    // ---------- 推荐官画像 + 声誉（PRD 五/六）：两条前缀分别落到不同上游 ----------

    @Test
    void routesRecommenderProfileToIdentityAndReputationToMarketplace() {
        assertThat(resolver.resolve("GET", "/api/recommenders/" + ACCOUNT_ID + "/profile")).isEqualTo(IDENTITY);
        assertThat(resolver.resolve("GET", "/api/reputation/" + ACCOUNT_ID)).isEqualTo(MARKETPLACE);
        // 自维护画像走既有 /api/me 前缀 → identity（不需要单独一条路由）
        assertThat(resolver.resolve("GET", "/api/me/recommender-profile")).isEqualTo(IDENTITY);
        assertThat(resolver.resolve("PUT", "/api/me/recommender-profile")).isEqualTo(IDENTITY);
    }

    @Test
    void reputationAndRecommendersAreInternalUpstreams() {
        assertThat(resolver.isInternalUpstream("GET", "/api/recommenders/" + ACCOUNT_ID + "/profile")).isTrue();
        assertThat(resolver.isInternalUpstream("GET", "/api/reputation/" + ACCOUNT_ID)).isTrue();
    }

    // ---------- intelligence Slice 1：/api/intelligence → intelligence（内部上游）----------

    @Test
    void routesIntelligenceSmokeToIntelligenceAndKeepsLegacyAiOnLegacy() {
        assertThat(resolver.resolve("POST", "/api/intelligence/smoke/chat")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.isInternalUpstream("POST", "/api/intelligence/smoke/chat")).isTrue();
        // 回归防护：脱口秀/文章文本端点已迁入 intelligence；图片评价等 legacy AI 工具仍走 legacy default upstream。
        assertThat(resolver.resolve("POST", "/api/image-analysis/analyze")).isEqualTo(LEGACY);
        // legacy 内部扣费端点（草场 intelligence → legacy credits，不经 BFF）即便经 BFF 也应落 legacy。
        assertThat(resolver.resolve("POST", "/api/internal/credits/consume")).isEqualTo(LEGACY);
    }

    @Test
    void routesComedyGenerationToIntelligence() {
        // Slice 2：脱口秀迁入 intelligence，路径沿用 legacy → 前端零改动。
        assertThat(resolver.resolve("POST", "/api/comedy-generation/generate-script")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.isInternalUpstream("POST", "/api/comedy-generation/generate-script")).isTrue();
    }

    @Test
    void routesArticleTextToIntelligenceButImagesStayLegacy() {
        // Slice 3：三文本端点 → intelligence。
        assertThat(resolver.resolve("POST", "/api/article-generation/titles")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("POST", "/api/article-generation/outline")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("POST", "/api/article-generation/content")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.isInternalUpstream("POST", "/api/article-generation/titles")).isTrue();
        // ⚠️ 回归防护：图片端点与文本端点共享 /api/article-generation 前缀，必须仍走 legacy（未迁）。
        // 若误把整段前缀路由到 intelligence，这些 legacy 图片功能会被抢走 → 404。
        assertThat(resolver.resolve("POST", "/api/article-generation/image-recommendations")).isEqualTo(LEGACY);
        assertThat(resolver.resolve("POST", "/api/article-generation/search-images")).isEqualTo(LEGACY);
        assertThat(resolver.resolve("POST", "/api/article-generation/generate-image")).isEqualTo(LEGACY);
        assertThat(resolver.resolve("GET", "/api/article-generation/generated-images/img-1")).isEqualTo(LEGACY);
    }

    @Test
    void routesVideoScriptToIntelligenceButVideoGenerationStaysLegacy() {
        assertThat(resolver.resolve("POST", "/api/video-production/generate-script")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.isInternalUpstream("POST", "/api/video-production/generate-script")).isTrue();
        // Seedance 集成仍是 legacy stub；精确路由不能抢走它。
        assertThat(resolver.resolve("POST", "/api/video-production/generate-video")).isEqualTo(LEGACY);
    }

    private static final String TASK_ID = "11111111-1111-1111-1111-111111111111";
    private static final String APP_ID = "22222222-2222-2222-2222-222222222222";
    private static final String ORG_ID = "33333333-3333-3333-3333-333333333333";
    private static final String ACCOUNT_ID = "44444444-4444-4444-4444-444444444444";
}
