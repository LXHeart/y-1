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
            // GL-P3-MERCHANT-001：KYB 审核队列 → identity（同样必须是精确前缀，不能退化为 /api/admin）
            new RouteProperties(null, "/api/admin/kyb-requests", "identity", true),
            // GL-P2-ADMIN-002：推荐官认证审核队列 → identity
            new RouteProperties(null, "/api/admin/recommender-requests", "identity", true),
            // GL-P3-AI-001：AI 控制面 → intelligence。/api/ai（BYOK keys + Run）全新无碰撞；
            // /api/admin/ai（模型配置 admin）精确前缀，不抢其它 /api/admin/*（同 kyb 口径）。
            new RouteProperties(null, "/api/ai", "intelligence", true),
            new RouteProperties(null, "/api/admin/ai", "intelligence", true),
            // Legacy 迁移：admin 用户管理 + 积分调整 → identity（排在更具体的 permission/kyb/ai 之后）
            new RouteProperties(null, "/api/admin/users", "identity", true),
            new RouteProperties(null, "/api/admin/adjust-credits", "identity", true),
            // GL-P2-ADMIN-006：财务对账台 → finance
            new RouteProperties(null, "/api/admin/finance", "finance", true),
            new RouteProperties(null, "/api/finance", "finance", true),
            // GL-P3-AI-001 下属切片：积分读端 → finance（balance/history，内部上游 → 签身份断言）
            new RouteProperties(null, "/api/credits", "finance", true),
            new RouteProperties(null, "/api/trust", "trust", true),
            // 推荐官画像 → identity，声誉 → marketplace（两个不同上游，前缀不得互相抢占）
            new RouteProperties(null, "/api/recommenders", "identity", true),
            new RouteProperties(null, "/api/reputation", "marketplace", true),
            // 运营处置台（GL-P1-OPS-001）→ marketplace
            new RouteProperties(null, "/api/ops", "marketplace", true),
            // intelligence Slice 1：/api/intelligence 前缀 → intelligence（冒烟端点 + 后续业务）
            new RouteProperties(null, "/api/intelligence", "intelligence", true),
            // intelligence Slice 8：media-reference 鉴权上传/签名读 → intelligence
            new RouteProperties(null, "/api/media", "intelligence", true),
            // intelligence Slice 2：/api/comedy-generation 前缀 → intelligence（脱口秀迁入，路径沿用 legacy）
            new RouteProperties(null, "/api/comedy-generation", "intelligence", true),
            // intelligence Slice 3：文章生成三文本端点 method+path 精确路由（图片端点与该前缀共享，仍走 legacy）
            new RouteProperties("POST", "/api/article-generation/titles", "intelligence", true, true),
            new RouteProperties("POST", "/api/article-generation/outline", "intelligence", true, true),
            new RouteProperties("POST", "/api/article-generation/content", "intelligence", true, true),
            // intelligence Slice 5：文章图片三个 POST 精确叶子 + generated-images GET 前缀
            new RouteProperties("POST", "/api/article-generation/image-recommendations", "intelligence", true, true),
            new RouteProperties("POST", "/api/article-generation/search-images", "intelligence", true, true),
            new RouteProperties("POST", "/api/article-generation/generate-image", "intelligence", true, true),
            new RouteProperties("GET", "/api/article-generation/generated-images", "intelligence", true),
            // intelligence Slice 4：视频制作脚本精确切换；generate-video stub 仍 legacy
            new RouteProperties("POST", "/api/video-production/generate-script", "intelligence", true, true),
            // intelligence Slice 9：视频改编出图 4 端点精确切换
            new RouteProperties("POST", "/api/video-recreation/generate-asset-image", "intelligence", true, true),
            new RouteProperties("POST", "/api/video-recreation/generate-all-asset-images", "intelligence", true, true),
            new RouteProperties("POST", "/api/video-recreation/generate-scene-image", "intelligence", true, true),
            new RouteProperties("POST", "/api/video-recreation/generate-all-scene-images", "intelligence", true, true),
            // intelligence Slice 10：adapt-content 精确切换（独立回滚开关；默认 false，测试构造时置 true 模拟开启）
            new RouteProperties("POST", "/api/video-recreation/adapt-content", "intelligence", true, true),
            // intelligence Slice 6：图片评价文案 9 端点精确切换（分三域回滚开关；前缀 /api/image-analysis 未整体路由）
            new RouteProperties("POST", "/api/image-analysis/analyze", "intelligence", true, true),
            new RouteProperties("POST", "/api/image-analysis/step/draft", "intelligence", true, true),
            new RouteProperties("POST", "/api/image-analysis/step/optimize", "intelligence", true, true),
            new RouteProperties("POST", "/api/image-analysis/step/style-refine", "intelligence", true, true),
            new RouteProperties("GET", "/api/image-analysis/style-preferences", "intelligence", true, true),
            new RouteProperties("PUT", "/api/image-analysis/style-preferences", "intelligence", true, true),
            new RouteProperties("POST", "/api/image-analysis/style-preferences/optimize", "intelligence", true, true),
            new RouteProperties("POST", "/api/image-analysis/save-style-memory", "intelligence", true, true),
            new RouteProperties("POST", "/api/image-analysis/export-feishu", "intelligence", true, true)),
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
    void routesCreditsReadsToFinanceAsInternalUpstream() {
        // GL-P3-AI-001 下属切片：积分 balance/history → finance；前缀窄，不抢 legacy /api/admin/*。
        assertThat(resolver.resolve("GET", "/api/credits/balance")).isEqualTo(FINANCE);
        assertThat(resolver.resolve("GET", "/api/credits/history")).isEqualTo(FINANCE);
        // 内部上游 → InternalAssertionFilter 签发 grassland-finance 用户断言，finance 才能解析 accountId。
        assertThat(resolver.isInternalUpstream("GET", "/api/credits/balance")).isTrue();
        // /api/credits 与 /api/admin 无前缀重叠（admin users 已迁 identity，见 adminUsersAndAdjustCreditsGoToIdentity）。
        assertThat(resolver.resolve("GET", "/api/credits/unknown-leaf")).isEqualTo(FINANCE);
    }

    @Test
    void creditsReadsFallBackToLegacyWhenFlagDisabled() {
        // 回滚：EDGE_ROUTE_CREDITS_FINANCE=false → /api/credits 回落 legacy default upstream。
        EdgeRoutingProperties disabled = new EdgeRoutingProperties(
            Map.of("legacy", LEGACY, "finance", FINANCE),
            List.of(new RouteProperties(null, "/api/credits", "finance", false)),
            "legacy");
        UpstreamResolver disabledResolver = new UpstreamResolver(disabled);
        assertThat(disabledResolver.resolve("GET", "/api/credits/balance")).isEqualTo(LEGACY);
    }

    @Test
    void adminPermissionRequestsGoesToIdentity() {
        // identity 的权限审核队列 → identity
        assertThat(resolver.resolve("GET", "/api/admin/permission-requests")).isEqualTo(IDENTITY);
        assertThat(resolver.resolve("POST", "/api/admin/permission-requests/req-1")).isEqualTo(IDENTITY);
        // /api/admin/users、/api/admin/adjust-credits 已迁 identity（见 adminUsersAndAdjustCreditsGoToIdentity）。
    }

    @Test
    void adminKybRequestsGoesToIdentity() {
        // GL-P3-MERCHANT-001：此前该前缀不在 RouteManifest 里 → 经 BFF 的 admin 审核请求全落 legacy 拿 404，
        // 审核闭环在 edge 层就断了（controller 再对也到不了）。
        assertThat(resolver.resolve("GET", "/api/admin/kyb-requests")).isEqualTo(IDENTITY);
        assertThat(resolver.resolve("POST", "/api/admin/kyb-requests/req-1/approve")).isEqualTo(IDENTITY);
        assertThat(resolver.resolve("POST", "/api/admin/kyb-requests/req-1/reject")).isEqualTo(IDENTITY);
        // GL-P2-ADMIN-002：推荐官认证审核队列 → identity
        assertThat(resolver.resolve("GET", "/api/admin/recommender-requests")).isEqualTo(IDENTITY);
        assertThat(resolver.resolve("POST", "/api/admin/recommender-requests/req-1/approve")).isEqualTo(IDENTITY);
        // 内部上游 → 会签身份断言，identity 侧才能解析出 admin 账号
        assertThat(resolver.isInternalUpstream("POST", "/api/admin/kyb-requests/req-1/approve")).isTrue();
        // /api/admin/users 也已迁 identity（kyb-requests 更具体，排在前面，不被抢占）
        assertThat(resolver.resolve("GET", "/api/admin/users")).isEqualTo(IDENTITY);
    }

    @Test
    void aiControlPlaneGoesToIntelligence() {
        // GL-P3-AI-001：/api/ai（BYOK keys + Run）此前不在 manifest → 经 BFF 落 legacy 404（隐性不可达）。
        assertThat(resolver.resolve("GET", "/api/ai/keys")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("POST", "/api/ai/runs")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("GET", "/api/ai/runs/" + java.util.UUID.randomUUID())).isEqualTo(INTELLIGENCE);
        // 平台模型配置 admin CRUD → intelligence
        assertThat(resolver.resolve("GET", "/api/admin/ai/models")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("POST", "/api/admin/ai/models")).isEqualTo(INTELLIGENCE);
        // 内部上游 → edge 签身份断言（admin role 经断言传播，intelligence 侧 requireAdmin 才能放行）
        assertThat(resolver.isInternalUpstream("POST", "/api/ai/runs")).isTrue();
        assertThat(resolver.isInternalUpstream("POST", "/api/admin/ai/models")).isTrue();
    }

    @Test
    void adminUsersAndAdjustCreditsGoToIdentity() {
        // Legacy 迁移：/api/admin/users + /api/admin/adjust-credits → identity
        assertThat(resolver.resolve("GET", "/api/admin/users")).isEqualTo(IDENTITY);
        assertThat(resolver.resolve("POST", "/api/admin/adjust-credits")).isEqualTo(IDENTITY);
        // 内部上游 → edge 签身份断言（admin role 经断言传播）
        assertThat(resolver.isInternalUpstream("GET", "/api/admin/users")).isTrue();
        assertThat(resolver.isInternalUpstream("POST", "/api/admin/adjust-credits")).isTrue();
        // ⚠️ 回归防护：/api/admin/users 路由不得抢走更具体的 /api/admin/* 路由
        assertThat(resolver.resolve("GET", "/api/admin/permission-requests")).isEqualTo(IDENTITY);
        assertThat(resolver.resolve("GET", "/api/admin/kyb-requests")).isEqualTo(IDENTITY);
        assertThat(resolver.resolve("GET", "/api/admin/ai/models")).isEqualTo(INTELLIGENCE);
    }

    @Test
    void newRoutesAreInternalUpstreamsSoAssertionGetsSigned() {
        assertThat(resolver.isInternalUpstream("POST", "/api/organizations")).isTrue();
        assertThat(resolver.isInternalUpstream("GET", "/api/me/identities")).isTrue();
        assertThat(resolver.isInternalUpstream("POST", "/api/finance/accounts")).isTrue();
        assertThat(resolver.isInternalUpstream("POST", "/api/trust/disputes")).isTrue();
        assertThat(resolver.isInternalUpstream("GET", "/api/admin/permission-requests")).isTrue();
        // admin 用户管理已迁 identity → 内部上游，edge 签断言（admin role 经断言传播）
        assertThat(resolver.isInternalUpstream("GET", "/api/admin/users")).isTrue();
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

    // ---------- 运营处置台（GL-P1-OPS-001）：/api/ops 三个子树同落 marketplace ----------

    @Test
    void routesOpsConsoleToMarketplace() {
        assertThat(resolver.resolve("GET", "/api/ops/cases")).isEqualTo(MARKETPLACE);
        assertThat(resolver.resolve("POST", "/api/ops/cases/" + ACCOUNT_ID + "/decide")).isEqualTo(MARKETPLACE);
        assertThat(resolver.resolve("GET", "/api/ops/dlt")).isEqualTo(MARKETPLACE);
        assertThat(resolver.resolve("GET", "/api/ops/pending-verifications")).isEqualTo(MARKETPLACE);
        // 内部上游 → edge 签发 X-Grassland-Identity，marketplace 侧才能按平台角色判闸门
        assertThat(resolver.isInternalUpstream("GET", "/api/ops/cases")).isTrue();
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
        // 回归防护：脱口秀/文章/图片评价/视频脚本已迁入 intelligence；抖音等 legacy AI 工具仍走 legacy default upstream。
        assertThat(resolver.resolve("POST", "/api/douyin/extract")).isEqualTo(LEGACY);
        // 图片评价未整体路由：精确 leaf 之外的子路径仍走 legacy。
        assertThat(resolver.resolve("POST", "/api/image-analysis/unknown-leaf")).isEqualTo(LEGACY);
        // legacy 内部扣费端点（草场 intelligence → legacy credits，不经 BFF）即便经 BFF 也应落 legacy。
        assertThat(resolver.resolve("POST", "/api/internal/credits/consume")).isEqualTo(LEGACY);
    }

    @Test
    void routesMediaReferenceToIntelligenceAsInternalUpstream() {
        assertThat(resolver.resolve("POST", "/api/media/upload-tickets")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("POST", "/api/media/" + APP_ID + "/confirm")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("GET", "/api/media/" + APP_ID)).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("DELETE", "/api/media/" + APP_ID)).isEqualTo(INTELLIGENCE);
        assertThat(resolver.isInternalUpstream("GET", "/api/media/" + APP_ID)).isTrue();
        assertThat(resolver.resolve("GET", "/api/medialibrary")).isEqualTo(LEGACY);
    }

    @Test
    void routesComedyGenerationToIntelligence() {
        // Slice 2：脱口秀迁入 intelligence，路径沿用 legacy → 前端零改动。
        assertThat(resolver.resolve("POST", "/api/comedy-generation/generate-script")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.isInternalUpstream("POST", "/api/comedy-generation/generate-script")).isTrue();
    }

    @Test
    void routesArticleTextAndImagesToIntelligenceWithoutStealingSiblings() {
        assertThat(resolver.resolve("POST", "/api/article-generation/titles")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("POST", "/api/article-generation/outline")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("POST", "/api/article-generation/content")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("POST", "/api/article-generation/image-recommendations"))
                .isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("POST", "/api/article-generation/search-images"))
                .isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("POST", "/api/article-generation/generate-image"))
                .isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("GET", "/api/article-generation/generated-images/" + APP_ID))
                .isEqualTo(INTELLIGENCE);
        assertThat(resolver.isInternalUpstream("POST", "/api/article-generation/generate-image")).isTrue();

        // method 与相近 sibling 必须回 legacy；精确叶子不能捕获子路径。
        assertThat(resolver.resolve("GET", "/api/article-generation/generate-image")).isEqualTo(LEGACY);
        assertThat(resolver.resolve("POST", "/api/article-generation/generated-images/" + APP_ID))
                .isEqualTo(LEGACY);
        assertThat(resolver.resolve("POST", "/api/article-generation/generate-image/other"))
                .isEqualTo(LEGACY);
        assertThat(resolver.resolve("POST", "/api/article-generation/search-images-preview"))
                .isEqualTo(LEGACY);
        assertThat(resolver.resolve("GET", "/api/article-generation/unknown")).isEqualTo(LEGACY);
    }

    @Test
    void routesVideoScriptToIntelligenceButVideoGenerationStaysLegacy() {
        assertThat(resolver.resolve("POST", "/api/video-production/generate-script")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.isInternalUpstream("POST", "/api/video-production/generate-script")).isTrue();
        // Seedance 集成仍是 legacy stub；精确路由不能抢走它。
        assertThat(resolver.resolve("POST", "/api/video-production/generate-video")).isEqualTo(LEGACY);
    }

    @Test
    void routesVideoRecreationLeavesToIntelligenceWithoutStealingSiblings() {
        // Slice 9：4 个出图端点精确切到 intelligence（内部上游 → 签发 X-Grassland-Identity）。
        assertThat(resolver.resolve("POST", "/api/video-recreation/generate-asset-image")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("POST", "/api/video-recreation/generate-all-asset-images")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("POST", "/api/video-recreation/generate-scene-image")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("POST", "/api/video-recreation/generate-all-scene-images")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.isInternalUpstream("POST", "/api/video-recreation/generate-scene-image")).isTrue();
        // Slice 10：adapt-content 精确切到 intelligence（开启回滚开关时）。
        assertThat(resolver.resolve("POST", "/api/video-recreation/adapt-content")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.isInternalUpstream("POST", "/api/video-recreation/adapt-content")).isTrue();
        // ⚠️ 回归防护：method 与子路径不抢——GET/sibling/extra 仍回 legacy。
        assertThat(resolver.resolve("GET", "/api/video-recreation/adapt-content")).isEqualTo(LEGACY);
        assertThat(resolver.resolve("GET", "/api/video-recreation/generate-scene-image")).isEqualTo(LEGACY);
        assertThat(resolver.resolve("POST", "/api/video-recreation/generate-asset-image/extra")).isEqualTo(LEGACY);
        assertThat(resolver.resolve("POST", "/api/video-recreation/unknown")).isEqualTo(LEGACY);
    }

    @Test
    void adaptContentFallsBackToLegacyWhenFlagDisabled() {
        // Slice 10 回滚：EDGE_ROUTE_VIDEO_RECREATION_ADAPTATION_INTELLIGENCE=false → 仍走 legacy。
        EdgeRoutingProperties disabled = new EdgeRoutingProperties(
            Map.of("legacy", LEGACY, "intelligence", INTELLIGENCE),
            List.of(new RouteProperties("POST", "/api/video-recreation/adapt-content", "intelligence", false, true)),
            "legacy");
        UpstreamResolver disabledResolver = new UpstreamResolver(disabled);
        assertThat(disabledResolver.resolve("POST", "/api/video-recreation/adapt-content")).isEqualTo(LEGACY);
    }

    @Test
    void routesImageAnalysisLeavesToIntelligenceWithoutStealingSiblings() {
        // 9 精确叶子 → intelligence
        assertThat(resolver.resolve("POST", "/api/image-analysis/analyze")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("POST", "/api/image-analysis/step/draft")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("POST", "/api/image-analysis/step/optimize")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("POST", "/api/image-analysis/step/style-refine")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("GET", "/api/image-analysis/style-preferences")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("PUT", "/api/image-analysis/style-preferences")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("POST", "/api/image-analysis/style-preferences/optimize")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("POST", "/api/image-analysis/save-style-memory")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("POST", "/api/image-analysis/export-feishu")).isEqualTo(INTELLIGENCE);
        // 内部上游 → 断言签发
        assertThat(resolver.isInternalUpstream("POST", "/api/image-analysis/analyze")).isTrue();
        assertThat(resolver.isInternalUpstream("GET", "/api/image-analysis/style-preferences")).isTrue();
        // method+path 精确：兄弟/未知子路径不抢
        assertThat(resolver.resolve("GET", "/api/image-analysis/style-preferences")).isEqualTo(INTELLIGENCE);
        assertThat(resolver.resolve("DELETE", "/api/image-analysis/style-preferences")).isEqualTo(LEGACY);
        assertThat(resolver.resolve("POST", "/api/image-analysis/style-preferences/optimize/extra")).isEqualTo(LEGACY);
        assertThat(resolver.resolve("GET", "/api/image-analysis/unknown")).isEqualTo(LEGACY);
    }

    // ---------- GL-P3-MEDIA-001：Douyin 媒体链路 → intelligence（extract/analyze 精确 + proxy/download 前缀）----------

    UpstreamResolver douyinMediaResolver() {
        EdgeRoutingProperties douyin = new EdgeRoutingProperties(
            Map.of("legacy", LEGACY, "intelligence", INTELLIGENCE),
            List.of(
                new RouteProperties("POST", "/api/douyin/extract-video", "intelligence", true, true),
                new RouteProperties("POST", "/api/douyin/analyze-video", "intelligence", true, true),
                new RouteProperties("GET", "/api/douyin/proxy", "intelligence", true),
                new RouteProperties("GET", "/api/douyin/download", "intelligence", true),
                new RouteProperties("GET", "/api/douyin/hot-items", "intelligence", true, true)),
            "legacy");
        return new UpstreamResolver(douyin);
    }

    @Test
    void routesDouyinMediaToIntelligenceWithoutStealingLegacyWorkers() {
        UpstreamResolver douyinResolver = douyinMediaResolver();
        assertThat(douyinResolver.resolve("POST", "/api/douyin/extract-video")).isEqualTo(INTELLIGENCE);
        assertThat(douyinResolver.resolve("POST", "/api/douyin/analyze-video")).isEqualTo(INTELLIGENCE);
        assertThat(douyinResolver.resolve("GET", "/api/douyin/proxy/token")).isEqualTo(INTELLIGENCE);
        assertThat(douyinResolver.resolve("GET", "/api/douyin/download/token")).isEqualTo(INTELLIGENCE);
        assertThat(douyinResolver.resolve("GET", "/api/douyin/hot-items")).isEqualTo(INTELLIGENCE);
        assertThat(douyinResolver.isInternalUpstream("POST", "/api/douyin/analyze-video")).isTrue();
        // audio/analysis-media/session 保留 legacy（FFmpeg/Playwright Node worker 边界）；exact 不抢兄弟路径。
        assertThat(douyinResolver.resolve("GET", "/api/douyin/audio/token")).isEqualTo(LEGACY);
        assertThat(douyinResolver.resolve("GET", "/api/douyin/analysis-media/x")).isEqualTo(LEGACY);
        assertThat(douyinResolver.resolve("GET", "/api/douyin/session")).isEqualTo(LEGACY);
        assertThat(douyinResolver.resolve("POST", "/api/douyin/session/start")).isEqualTo(LEGACY);
        assertThat(douyinResolver.resolve("POST", "/api/douyin/extract")).isEqualTo(LEGACY);
        assertThat(douyinResolver.resolve("GET", "/api/douyin/hot-items/extra")).isEqualTo(LEGACY);
    }

    @Test
    void douyinMediaFallsBackToLegacyWhenFlagDisabled() {
        // 回滚：EDGE_ROUTE_DOUYIN_MEDIA_INTELLIGENCE=false → 全部回落 legacy。
        EdgeRoutingProperties disabled = new EdgeRoutingProperties(
            Map.of("legacy", LEGACY, "intelligence", INTELLIGENCE),
            List.of(
                new RouteProperties("POST", "/api/douyin/extract-video", "intelligence", false, true),
                new RouteProperties("POST", "/api/douyin/analyze-video", "intelligence", false, true),
                new RouteProperties("GET", "/api/douyin/proxy", "intelligence", false),
                new RouteProperties("GET", "/api/douyin/download", "intelligence", false)),
            "legacy");
        UpstreamResolver disabledResolver = new UpstreamResolver(disabled);
        assertThat(disabledResolver.resolve("POST", "/api/douyin/extract-video")).isEqualTo(LEGACY);
        assertThat(disabledResolver.resolve("POST", "/api/douyin/analyze-video")).isEqualTo(LEGACY);
        assertThat(disabledResolver.resolve("GET", "/api/douyin/proxy/token")).isEqualTo(LEGACY);
        assertThat(disabledResolver.resolve("GET", "/api/douyin/download/token")).isEqualTo(LEGACY);
    }

    private static final String TASK_ID = "11111111-1111-1111-1111-111111111111";
    private static final String APP_ID = "22222222-2222-2222-2222-222222222222";
    private static final String ORG_ID = "33333333-3333-3333-3333-333333333333";
    private static final String ACCOUNT_ID = "44444444-4444-4444-4444-444444444444";
}
