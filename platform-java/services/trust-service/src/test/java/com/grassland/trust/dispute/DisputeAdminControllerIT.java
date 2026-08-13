package com.grassland.trust.dispute;

import static org.assertj.core.api.Assertions.assertThat;
import static com.grassland.identity.assertion.TestAssertionHelper.userSigner;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.trust.TrustItSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DisputeAdminControllerIT extends TrustItSupport {

    @Test
    void supportQueueUsesStablePremiumFirstKeysetPaginationWithoutDuplicates() {
        // Trust IT 共用单例数据库；本用例只隔离活跃队列，不删除历史/审计数据。
        db.sql("UPDATE dispute_case SET status='final' WHERE status <> 'final'").then().block();
        String premiumOld = seedCase(true, 100, "2000-01-01T00:00:00Z");
        String premiumNew = seedCase(true, 100, "2000-01-02T00:00:00Z");
        String standardOld = seedCase(false, 0, "2000-01-01T00:00:00Z");
        String standardNew = seedCase(false, 0, "2000-01-02T00:00:00Z");
        String assertion = signRole(UUID.randomUUID().toString(), "customer_service", false);

        Map<?, ?> first = client().get().uri("/api/admin/trust/disputes?limit=2")
                .header("X-Grassland-Identity", assertion)
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        Map<?, ?> firstData = (Map<?, ?>) first.get("data");
        List<?> firstItems = (List<?>) firstData.get("items");
        assertThat(itemId(firstItems, 0)).isEqualTo(premiumOld);
        assertThat(itemId(firstItems, 1)).isEqualTo(premiumNew);
        assertThat(((Map<?, ?>) firstItems.getFirst()).get("premiumSupport")).isEqualTo(true);
        assertThat(((Map<?, ?>) firstItems.getFirst()).get("supportPriority")).isEqualTo(100);
        assertThat(((Map<?, ?>) firstItems.getFirst()).get("supportBadge")).isEqualTo("premium");
        String cursor = (String) firstData.get("nextCursor");
        assertThat(cursor).isNotBlank();

        Map<?, ?> second = client().get().uri(uriBuilder -> uriBuilder.path("/api/admin/trust/disputes")
                        .queryParam("limit", 2).queryParam("cursor", cursor).build())
                .header("X-Grassland-Identity", assertion)
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        List<?> secondItems = (List<?>) ((Map<?, ?>) second.get("data")).get("items");
        assertThat(itemId(secondItems, 0)).isEqualTo(standardOld);
        assertThat(itemId(secondItems, 1)).isEqualTo(standardNew);
        assertThat(List.of(itemId(firstItems, 0), itemId(firstItems, 1),
                itemId(secondItems, 0), itemId(secondItems, 1))).doesNotHaveDuplicates();
    }

    @Test
    void supportQueueAllowsOnlyCustomerServiceAndPlatformAdmin() {
        for (String role : List.of("customer_service", "platform_admin")) {
            client().get().uri("/api/admin/trust/disputes")
                    .header("X-Grassland-Identity", signRole(UUID.randomUUID().toString(), role, false))
                    .exchange().expectStatus().isOk();
        }
        client().get().uri("/api/admin/trust/disputes").exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/admin/trust/disputes")
                .header("X-Grassland-Identity", signRole(UUID.randomUUID().toString(), "risk", false))
                .exchange().expectStatus().isForbidden();
        client().get().uri("/api/admin/trust/disputes")
                .header("X-Grassland-Identity", signRole("service:marketplace", "platform_admin", true))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void supportQueueRejectsInvalidCursorAndUnboundedLimit() {
        String assertion = signRole(UUID.randomUUID().toString(), "platform_admin", false);
        client().get().uri("/api/admin/trust/disputes?cursor=not-a-cursor")
                .header("X-Grassland-Identity", assertion)
                .exchange().expectStatus().isBadRequest();
        client().get().uri("/api/admin/trust/disputes?limit=101")
                .header("X-Grassland-Identity", assertion)
                .exchange().expectStatus().isBadRequest();
    }

    private String seedCase(boolean premium, int priority, String createdAt) {
        String id = UUID.randomUUID().toString();
        db.sql("""
                INSERT INTO dispute_case(id, engagement_ref, organization_id, opened_by_account_id,
                    opened_by_role, status, reason, kind, premium_support, support_priority, created_at, updated_at)
                VALUES (CAST(:id AS uuid), :engagement, CAST(:org AS uuid), CAST(:openedBy AS uuid),
                    'merchant', 'open', 'support queue test', 'standard', :premium, :priority,
                    CAST(:createdAt AS timestamptz), CAST(:createdAt AS timestamptz))
                """)
                .bind("id", id).bind("engagement", UUID.randomUUID().toString())
                .bind("org", UUID.randomUUID().toString()).bind("openedBy", UUID.randomUUID().toString())
                .bind("premium", premium).bind("priority", priority).bind("createdAt", createdAt)
                .then().block();
        return id;
    }

    private String signRole(String accountId, String role, boolean service) {
        if (service) {
            return signServiceWithRole(null, "marketplace", role);
        }
        Instant now = Instant.now();
        return userSigner("edge-bff", "grassland-trust").sign(new IdentityAssertion(
                accountId, null, "sid-" + accountId, null, null,
                "cookie-session", "level2", now, "r", "t",
                "grassland-trust", now, now.plusSeconds(60), null, null, role));
    }

    private static String itemId(List<?> items, int index) {
        return (String) ((Map<?, ?>) items.get(index)).get("id");
    }
}
