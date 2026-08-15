package com.grassland.marketplace.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class RecommenderMatchingControllerIT extends MarketplaceItSupport {

    @Test
    @SuppressWarnings("unchecked")
    void merchantGetsExplainableStableRankingFromMarketplaceFacts() {
        Fixture fixture = fixture();
        String strong = candidateWithHistory("douyin", true, 5, 12);
        String weak = candidateWithHistory("xiaohongshu", false, null, null);

        Map<String, Object> response = client().get().uri("/api/tasks/" + fixture.taskId() + "/recommendations?limit=100")
                .header("X-Grassland-Identity", sign(
                        fixture.merchantId(), "merchant", fixture.organizationId(), "basic_publish"))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> data = data(response);
        assertThat(data.get("scoringVersion")).isEqualTo("deterministic-v1");
        assertThat((Integer) data.get("eligibleCount")).isGreaterThanOrEqualTo(2);
        @SuppressWarnings("unchecked")
        var items = (java.util.List<Map<String, Object>>) data.get("items");
        int strongIndex = indexOf(items, strong);
        int weakIndex = indexOf(items, weak);
        assertThat(strongIndex).isGreaterThanOrEqualTo(0).isLessThan(weakIndex);
        Map<String, Object> strongMatch = items.get(strongIndex);
        assertThat(strongMatch.get("totalScore")).isInstanceOf(Number.class);
        @SuppressWarnings("unchecked")
        var dimensions = (java.util.List<Map<String, Object>>) strongMatch.get("dimensions");
        assertThat(dimensions).hasSize(6);
        assertThat(dimensions.getFirst()).containsEntry("key", "platformFit").containsEntry("score", 15);
        @SuppressWarnings("unchecked")
        var reasons = (java.util.List<String>) strongMatch.get("reasons");
        assertThat(reasons).hasSize(3);
    }

    @Test
    void onlyTaskManagerCanReadOrInvite() {
        Fixture fixture = fixture();
        String candidate = candidateWithHistory("douyin", false, null, null);

        client().get().uri("/api/tasks/" + fixture.taskId() + "/recommendations")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isForbidden();
        client().post().uri("/api/tasks/" + fixture.taskId() + "/recommendations/" + candidate + "/invite")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant",
                        fixture.organizationId(), "basic_publish"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void invitationIsIdempotentFreezesScoreAndEmitsOneEvent() {
        Fixture fixture = fixture();
        String candidate = candidateWithHistory("douyin", true, 4, 24);
        String path = "/api/tasks/" + fixture.taskId() + "/recommendations/" + candidate + "/invite";

        Map<String, Object> first = invite(path, fixture);
        Map<String, Object> replay = invite(path, fixture);

        assertThat(first.get("created")).isEqualTo(true);
        assertThat(replay.get("created")).isEqualTo(false);
        assertThat(replay.get("id")).isEqualTo(first.get("id"));
        Integer invitations = db.sql("SELECT COUNT(*)::int c FROM task_recommender_invitation"
                        + " WHERE task_id=CAST(:task AS uuid) AND recommender_account_id=CAST(:candidate AS uuid)")
                .bind("task", fixture.taskId()).bind("candidate", candidate)
                .map(row -> row.get("c", Integer.class)).one().block();
        Integer events = db.sql("SELECT COUNT(*)::int c FROM marketplace_outbox"
                        + " WHERE event_type='TaskRecommenderInvited' AND payload->>'taskId'=:task")
                .bind("task", fixture.taskId()).map(row -> row.get("c", Integer.class)).one().block();
        String version = db.sql("SELECT score_snapshot->>'scoringVersion' v FROM task_recommender_invitation"
                        + " WHERE id=CAST(:id AS uuid)")
                .bind("id", first.get("id")).map(row -> row.get("v", String.class)).one().block();
        String recipient = db.sql("SELECT payload->>'recommenderAccountId' v FROM marketplace_outbox"
                        + " WHERE event_type='TaskRecommenderInvited' AND payload->>'taskId'=:task")
                .bind("task", fixture.taskId()).map(row -> row.get("v", String.class)).one().block();
        assertThat(invitations).isEqualTo(1);
        assertThat(events).isEqualTo(1);
        assertThat(version).isEqualTo("deterministic-v1");
        assertThat(recipient).isEqualTo(candidate);
    }

    @Test
    void applyingThroughNormalEndpointClosesInvitationAndRemovesCandidate() {
        Fixture fixture = fixture();
        String candidate = candidateWithHistory("douyin", false, null, null);
        invite("/api/tasks/" + fixture.taskId() + "/recommendations/" + candidate + "/invite", fixture);

        client().post().uri("/api/tasks/" + fixture.taskId() + "/applications")
                .header("X-Grassland-Identity", sign(candidate, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "应邀报名"))
                .exchange().expectStatus().isCreated();

        Instant appliedAt = db.sql("SELECT applied_at FROM task_recommender_invitation"
                        + " WHERE task_id=CAST(:task AS uuid) AND recommender_account_id=CAST(:candidate AS uuid)")
                .bind("task", fixture.taskId()).bind("candidate", candidate)
                .map(row -> row.get("applied_at", java.time.OffsetDateTime.class).toInstant()).one().block();
        assertThat(appliedAt).isNotNull();
        client().get().uri("/api/tasks/" + fixture.taskId() + "/recommendations")
                .header("X-Grassland-Identity", sign(
                        fixture.merchantId(), "merchant", fixture.organizationId(), "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items[?(@.accountId == '" + candidate + "')]").isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invite(String path, Fixture fixture) {
        Map<String, Object> response = client().post().uri(path)
                .header("X-Grassland-Identity", sign(
                        fixture.merchantId(), "merchant", fixture.organizationId(), "basic_publish"))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        return (Map<String, Object>) response.get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> response) {
        return (Map<String, Object>) response.get("data");
    }

    private static int indexOf(java.util.List<Map<String, Object>> items, String accountId) {
        for (int index = 0; index < items.size(); index++) {
            if (accountId.equals(items.get(index).get("accountId"))) return index;
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture() {
        String merchant = UUID.randomUUID().toString();
        String organization = UUID.randomUUID().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", organization);
        body.put("title", "抖音探店任务");
        body.put("platform", "douyin");
        body.put("contentForm", "video");
        body.put("maxSlots", 10);
        Map<String, Object> response = client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(
                        merchant, "merchant", organization, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String taskId = (String) ((Map<String, Object>) response.get("data")).get("id");
        db.sql("UPDATE task SET status='published', published_at=now() WHERE id=CAST(:id AS uuid)")
                .bind("id", taskId).then().block();
        return new Fixture(merchant, organization, taskId);
    }

    private String candidateWithHistory(
            String platform, boolean completed, Integer rating, Integer responseHours) {
        String candidate = UUID.randomUUID().toString();
        String oldTask = UUID.randomUUID().toString();
        String oldOwner = UUID.randomUUID().toString();
        String oldOrg = UUID.randomUUID().toString();
        String application = UUID.randomUUID().toString();
        db.sql("""
                INSERT INTO task(id, owner_account_id, organization_id, title, status, platform)
                VALUES(CAST(:id AS uuid), CAST(:owner AS uuid), CAST(:org AS uuid), '历史任务', 'published', :platform)
                """).bind("id", oldTask).bind("owner", oldOwner).bind("org", oldOrg).bind("platform", platform)
                .then().block();
        var insert = db.sql("""
                INSERT INTO task_application(
                    id, task_id, recommender_account_id, status, decided_at, confirmed_at, bounty_cents,
                    reputation_level_at_accept, reputation_policy_version_at_accept,
                    settlement_delay_days_at_accept, commission_bonus_bps_at_accept,
                    premium_support_at_accept)
                VALUES(CAST(:id AS uuid), CAST(:task AS uuid), CAST(:candidate AS uuid), :status,
                       now() - interval '10 days', %s, 0, %s)
                """.formatted(
                        completed ? "now() - interval '8 days'" : "NULL",
                        completed ? "1, 1, 2, 0, false" : "NULL, NULL, NULL, NULL, NULL"))
                .bind("id", application).bind("task", oldTask).bind("candidate", candidate)
                .bind("status", completed ? "accepted" : "pending");
        insert.then().block();
        if (responseHours != null) {
            db.sql("""
                    INSERT INTO engagement_submission(
                        id, application_id, recommender_account_id, content_url, status, created_at)
                    VALUES(gen_random_uuid(), CAST(:application AS uuid), CAST(:candidate AS uuid),
                           'https://example.com/work', 'accepted',
                           now() - interval '10 days' + (:hours * interval '1 hour'))
                    """).bind("application", application).bind("candidate", candidate).bind("hours", responseHours)
                    .then().block();
        }
        if (rating != null) {
            db.sql("""
                    INSERT INTO engagement_rating(
                        id, application_id, task_id, recommender_account_id, rated_by_account_id, score)
                    VALUES(gen_random_uuid(), CAST(:application AS uuid), CAST(:task AS uuid),
                           CAST(:candidate AS uuid), CAST(:owner AS uuid), :score)
                    """).bind("application", application).bind("task", oldTask).bind("candidate", candidate)
                    .bind("owner", oldOwner).bind("score", rating).then().block();
        }
        return candidate;
    }

    private record Fixture(String merchantId, String organizationId, String taskId) {}
}
