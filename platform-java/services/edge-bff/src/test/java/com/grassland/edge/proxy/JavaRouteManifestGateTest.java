package com.grassland.edge.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * JBE-01/JBE-04 gate: every enabled production route is Java-owned and unknown APIs fail closed.
 */
@SpringBootTest(properties = {
        "management.server.port=0",
        "PUBLIC_BACKEND_ORIGIN=http://localhost:8080",
        "BILIBILI_PROXY_TOKEN_SECRET=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
        "DOUYIN_PROXY_TOKEN_SECRET=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
})
class JavaRouteManifestGateTest {

    @Autowired
    private EdgeRoutingProperties properties;

    @Autowired
    private UpstreamResolver resolver;

    @Test
    void everyEnabledManifestRouteTargetsAJavaUpstream() {
        assertThat(properties.defaultUpstream()).isEqualTo(EdgeRoutingProperties.FAIL_CLOSED);
        assertThat(properties.upstreams()).doesNotContainKey("legacy");
        assertThat(properties.routes())
                .filteredOn(RouteProperties::enabled)
                .allSatisfy(route -> {
                    assertThat(properties.upstreams()).containsKey(route.upstream());
                });
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @MethodSource("representativeJavaRoutes")
    void everyPublicJavaRouteFamilyResolvesWithoutLegacyFallback(
            String method, String path, String expectedUpstream) {
        assertThat(resolver.resolveUpstreamName(method, path)).isEqualTo(expectedUpstream);
        assertThat(resolver.isInternalUpstream(method, path)).isTrue();
    }

    @ParameterizedTest(name = "fail-closed boundary: {0} {1}")
    @MethodSource("failClosedBoundaries")
    void unknownOrMethodMismatchedPathsFailClosed(String method, String path) {
        assertThat(resolver.resolveUpstreamName(method, path)).isEqualTo(EdgeRoutingProperties.FAIL_CLOSED);
        assertThat(resolver.resolve(method, path)).isNull();
        assertThat(resolver.isInternalUpstream(method, path)).isFalse();
    }

    private static Stream<Arguments> representativeJavaRoutes() {
        return Stream.of(
                route("GET", "/api/auth/me", "identity"),
                route("POST", "/api/auth/login", "identity"),
                route("POST", "/api/auth/logout", "identity"),
                route("GET", "/api/auth/captcha", "identity"),
                route("POST", "/api/auth/send-code", "identity"),
                route("POST", "/api/auth/register", "identity"),
                route("POST", "/api/auth/refresh", "identity"),
                route("POST", "/api/auth/revoke", "identity"),
                route("GET", "/api/tasks/feed", "marketplace"),
                route("GET", "/api/analytics/overview", "marketplace"),
                route("GET", "/api/v2/commerce/offers", "marketplace"),
                route("GET", "/api/organizations/org-1/stores", "identity"),
                route("GET", "/api/me/identities", "identity"),
                route("GET", "/api/admin/permission-requests", "identity"),
                route("GET", "/api/admin/kyb-requests", "identity"),
                route("GET", "/api/admin/recommender-requests", "identity"),
                route("POST", "/api/ai/runs", "intelligence"),
                // 任务书 #36：游客试用窄面（flag on 时路由、off 时 fail-closed 404，见 flagOff 测试）。
                route("POST", "/api/guest-trial/article-titles", "intelligence"),
                route("POST", "/api/content-safety/check", "intelligence"),
                route("GET", "/api/guest-trial/quota", "intelligence"),
                route("GET", "/api/admin/ai/models", "intelligence"),
                route("GET", "/api/admin/ai/video-reconciliation", "intelligence"),
                route("GET", "/api/admin/finance/reconciliation", "finance"),
                route("GET", "/api/admin/credits-packages", "finance"),
                route("GET", "/api/admin/credits-purchase-orders", "finance"),
                route("GET", "/api/admin/commerce/orders", "marketplace"),
                route("GET", "/api/admin/analytics/overview", "marketplace"),
                route("GET", "/api/admin/tasks/review", "marketplace"),
                route("GET", "/api/admin/reputation-config", "marketplace"),
                route("GET", "/api/admin/reputation/accounts/account-1", "marketplace"),
                route("GET", "/api/admin/trust/judges", "trust"),
                route("GET", "/api/content-assets", "intelligence"),
                route("POST", "/api/admin/content-assets/review", "intelligence"),
                route("POST", "/api/creation-assistant/score", "intelligence"),
                route("GET", "/api/creation-drafts/draft-1", "intelligence"),
                route("POST", "/api/creation-contexts", "intelligence"),
                route("GET", "/api/admin/users", "identity"),
                route("POST", "/api/admin/adjust-credits", "identity"),
                route("GET", "/api/recommenders/account-1/profile", "identity"),
                route("GET", "/api/ops/cases", "marketplace"),
                route("GET", "/api/reputation/me", "marketplace"),
                route("GET", "/api/finance/wallets/me", "finance"),
                route("GET", "/api/credits/balance", "finance"),
                route("GET", "/api/trust/disputes/dispute-1", "trust"),
                route("GET", "/api/intelligence/smoke/chat", "intelligence"),
                route("GET", "/api/settings/analysis", "intelligence"),
                route("GET", "/api/homepage/hot-items", "intelligence"),
                route("POST", "/api/media/upload-tickets", "intelligence"),
                route("POST", "/api/comedy-generation/generate-script", "intelligence"),
                route("POST", "/api/moments-generation/generate", "intelligence"),
                route("POST", "/api/article-generation/titles", "intelligence"),
                route("POST", "/api/article-generation/outline", "intelligence"),
                route("POST", "/api/article-generation/content", "intelligence"),
                route("POST", "/api/article-generation/image-recommendations", "intelligence"),
                route("POST", "/api/article-generation/search-images", "intelligence"),
                route("POST", "/api/article-generation/generate-image", "intelligence"),
                route("GET", "/api/article-generation/generated-images/image-1", "intelligence"),
                route("POST", "/api/video-production/generate-script", "intelligence"),
                route("GET", "/api/video-production/capabilities", "intelligence"),
                route("POST", "/api/video-production/generate-video", "intelligence"),
                route("GET", "/api/video-production/jobs/job-1", "intelligence"),
                route("GET", "/api/video-production/jobs/job-1/download-url", "intelligence"),
                route("POST", "/api/video-production/jobs/job-1/cancel", "intelligence"),
                route("POST", "/api/video-recreation/generate-asset-image", "intelligence"),
                route("POST", "/api/video-recreation/generate-all-asset-images", "intelligence"),
                route("POST", "/api/video-recreation/generate-scene-image", "intelligence"),
                route("POST", "/api/video-recreation/generate-all-scene-images", "intelligence"),
                route("POST", "/api/video-recreation/adapt-content", "intelligence"),
                route("POST", "/api/image-analysis/analyze", "intelligence"),
                route("POST", "/api/image-analysis/step/draft", "intelligence"),
                route("POST", "/api/image-analysis/step/optimize", "intelligence"),
                route("POST", "/api/image-analysis/step/style-refine", "intelligence"),
                route("GET", "/api/image-analysis/style-preferences", "intelligence"),
                route("PUT", "/api/image-analysis/style-preferences", "intelligence"),
                route("POST", "/api/image-analysis/style-preferences/optimize", "intelligence"),
                route("POST", "/api/image-analysis/save-style-memory", "intelligence"),
                route("POST", "/api/image-analysis/export-feishu", "intelligence"),
                route("GET", "/api/douyin/hot-items", "intelligence"),
                route("POST", "/api/bilibili/extract-video", "intelligence"),
                route("POST", "/api/bilibili/analyze-video", "intelligence"),
                route("GET", "/api/bilibili/proxy/token", "intelligence"),
                route("GET", "/api/bilibili/download/token", "intelligence"),
                route("GET", "/api/bilibili/analysis-media/media-1", "intelligence"),
                route("POST", "/api/douyin/extract-video", "intelligence"),
                route("POST", "/api/douyin/analyze-video", "intelligence"),
                route("GET", "/api/douyin/proxy/token", "intelligence"),
                route("GET", "/api/douyin/download/token", "intelligence"),
                route("GET", "/api/douyin/audio/token", "intelligence"),
                route("GET", "/api/douyin/analysis-media/media-1", "intelligence"),
                route("POST", "/api/douyin/session/login", "intelligence"));
    }

    private static Stream<Arguments> failClosedBoundaries() {
        return Stream.of(
                Arguments.of("GET", "/api/not-migrated"),
                Arguments.of("GET", "/api/auth/login"),
                Arguments.of("GET", "/api/article-generation/titles"),
                Arguments.of("GET", "/api/video-recreation/adapt-content"),
                Arguments.of("GET", "/api/image-analysis/analyze"));
    }

    private static Arguments route(String method, String path, String upstream) {
        return Arguments.of(method, path, upstream);
    }
}
