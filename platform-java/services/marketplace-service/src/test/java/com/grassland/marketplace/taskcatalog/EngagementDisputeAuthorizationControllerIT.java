package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 争议参与方授权端点（Slice 12 安全收口）。marketplace 是 engagement 参与方与 canonical task organization 的权威：
 * 仅 {@code principal=trust} 服务断言可调；merchant 须为 task owner，recommender 须为 application recommender。
 * 通过 SQL 直接 seed task/application（避免 3 次 HTTP 建链开销），聚焦授权判定。
 */
class EngagementDisputeAuthorizationControllerIT extends MarketplaceItSupport {

    private static final String ENDPOINT = "/internal/marketplace/engagements/{id}/dispute-authorization";

    @Test
    void trustServiceAuthorizesTaskOwnerAndReturnsCanonicalOrg() {
        String org = UUID.randomUUID().toString();
        String owner = UUID.randomUUID().toString();
        Seeded eng = seedAcceptedApplication(org, owner, UUID.randomUUID().toString());

        client().post().uri(ENDPOINT, eng.applicationId)
                .header("X-Grassland-Identity", signService("trust"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("actorAccountId", owner, "actorIdentity", "merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.engagementRef").isEqualTo(eng.applicationId)
                .jsonPath("$.data.organizationId").isEqualTo(org);
    }

    @Test
    void trustServiceAuthorizesApplicationRecommender() {
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        Seeded eng = seedAcceptedApplication(org, UUID.randomUUID().toString(), recommender);

        client().post().uri(ENDPOINT, eng.applicationId)
                .header("X-Grassland-Identity", signService("trust"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("actorAccountId", recommender, "actorIdentity", "recommender"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.organizationId").isEqualTo(org);
    }

    @Test
    void nonPartyAccountIsForbidden() {
        Seeded eng = seedAcceptedApplication(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString());

        client().post().uri(ENDPOINT, eng.applicationId)
                .header("X-Grassland-Identity", signService("trust"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("actorAccountId", UUID.randomUUID().toString(), "actorIdentity", "merchant"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void rolePartyMismatchIsForbidden() {
        String owner = UUID.randomUUID().toString();
        Seeded eng = seedAcceptedApplication(UUID.randomUUID().toString(), owner, UUID.randomUUID().toString());

        // owner 用 recommender 身份发起 → 角色与参与关系不符
        client().post().uri(ENDPOINT, eng.applicationId)
                .header("X-Grassland-Identity", signService("trust"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("actorAccountId", owner, "actorIdentity", "recommender"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void userAssertionCannotCallInternalEndpoint() {
        Seeded eng = seedAcceptedApplication(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString());

        client().post().uri(ENDPOINT, eng.applicationId)
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("actorAccountId", UUID.randomUUID().toString(), "actorIdentity", "merchant"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void nonAcceptedApplicationConflicts() {
        String org = UUID.randomUUID().toString();
        String owner = UUID.randomUUID().toString();
        String app = UUID.randomUUID().toString();
        String task = seedTask(org, owner);
        db.sql("INSERT INTO task_application(id, task_id, recommender_account_id, status, bounty_cents)"
                + " VALUES (CAST(:id AS uuid), CAST(:task AS uuid), CAST(:rec AS uuid), 'pending', 0)")
                .bind("id", app).bind("task", task).bind("rec", UUID.randomUUID().toString()).then().block();

        client().post().uri(ENDPOINT, app)
                .header("X-Grassland-Identity", signService("trust"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("actorAccountId", owner, "actorIdentity", "merchant"))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void missingApplicationNotFound() {
        client().post().uri(ENDPOINT, UUID.randomUUID().toString())
                .header("X-Grassland-Identity", signService("trust"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("actorAccountId", UUID.randomUUID().toString(), "actorIdentity", "merchant"))
                .exchange().expectStatus().isNotFound();
    }

    /** seed published task + accepted application，返回 applicationId。 */
    private Seeded seedAcceptedApplication(String org, String owner, String recommender) {
        String task = seedTask(org, owner);
        String app = UUID.randomUUID().toString();
        db.sql("INSERT INTO task_application(id, task_id, recommender_account_id, status, bounty_cents)"
                + " VALUES (CAST(:id AS uuid), CAST(:task AS uuid), CAST(:rec AS uuid), 'accepted', 0)")
                .bind("id", app).bind("task", task).bind("rec", recommender).then().block();
        return new Seeded(app, task);
    }

    private String seedTask(String org, String owner) {
        String task = UUID.randomUUID().toString();
        db.sql("INSERT INTO task(id, owner_account_id, organization_id, title, status)"
                + " VALUES (CAST(:id AS uuid), CAST(:owner AS uuid), CAST(:org AS uuid), :title, 'published')")
                .bind("id", task).bind("owner", owner).bind("org", org).bind("title", "授权 e2e 任务").then().block();
        return task;
    }

    private record Seeded(String applicationId, String taskId) {}
}
