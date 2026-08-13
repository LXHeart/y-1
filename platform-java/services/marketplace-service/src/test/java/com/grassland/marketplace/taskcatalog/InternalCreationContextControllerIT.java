package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.MarketplaceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Internal authoritative handoff for PRD §4.12 creation-context snapshots. */
class InternalCreationContextControllerIT extends MarketplaceItSupport {

    @Test
    void acceptedRecommenderCanReadFrozenTaskContext() {
        Fixture fixture = acceptedFixture();

        client().post().uri("/internal/marketplace/engagements/" + fixture.applicationId()
                        + "/creation-context")
                .header("X-Grassland-Identity", signService("intelligence"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("taskId", fixture.taskId(),
                        "recommenderAccountId", fixture.recommenderAccountId()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.organizationId").isEqualTo(fixture.organizationId())
                .jsonPath("$.data.taskContext.storeId").isEqualTo(fixture.storeId())
                .jsonPath("$.data.taskContext.taskId").isEqualTo(fixture.taskId())
                .jsonPath("$.data.taskContext.applicationId").isEqualTo(fixture.applicationId())
                .jsonPath("$.data.taskContext.recommenderAccountId")
                .isEqualTo(fixture.recommenderAccountId())
                .jsonPath("$.data.taskContext.taskVersion").isEqualTo(1)
                .jsonPath("$.data.taskContext.platform").isEqualTo("xiaohongshu")
                .jsonPath("$.data.taskContext.contentForm").isEqualTo("graphic")
                .jsonPath("$.data.taskContext.requirements.productServiceInfo").isEqualTo("双人招牌套餐")
                .jsonPath("$.data.taskContext.requirements.mustInclude[0]").isEqualTo("门店名")
                .jsonPath("$.data.taskContext.requirements.forbiddenContent[0]").isEqualTo("绝对化功效")
                .jsonPath("$.data.taskContext.requirements.publishStartAt").isEqualTo("2026-08-20T10:00:00Z")
                .jsonPath("$.data.taskContext.requirements.publishEndAt").isEqualTo("2026-08-25T10:00:00Z")
                .jsonPath("$.data.taskContext.requirements.metricRequirements[0]").isEqualTo("发布后 24 小时播放量")
                .jsonPath("$.data.taskContext.requirements.evidenceRequirements[0]").isEqualTo("发布链接");
    }

    @Test
    void rejectsWrongServiceAndNonParticipant() {
        Fixture fixture = acceptedFixture();
        String uri = "/internal/marketplace/engagements/" + fixture.applicationId()
                + "/creation-context";
        Map<String, Object> valid = Map.of("taskId", fixture.taskId(),
                "recommenderAccountId", fixture.recommenderAccountId());

        client().post().uri(uri)
                .header("X-Grassland-Identity", signService("trust"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(valid)
                .exchange().expectStatus().isForbidden();

        client().post().uri(uri)
                .header("X-Grassland-Identity", signService("intelligence"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("taskId", fixture.taskId(),
                        "recommenderAccountId", UUID.randomUUID().toString()))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void rejectsApplicationThatWasNotAccepted() {
        String taskId = UUID.randomUUID().toString();
        String appId = UUID.randomUUID().toString();
        String merchant = UUID.randomUUID().toString();
        String organization = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        db.sql("INSERT INTO task(id, owner_account_id, organization_id, title, status, version) "
                        + "VALUES (CAST(:id AS uuid), CAST(:owner AS uuid), CAST(:org AS uuid),"
                        + " 'draft task', 'published', 1)")
                .bind("id", taskId).bind("owner", merchant).bind("org", organization).then().block();
        db.sql("INSERT INTO task_application(id, task_id, recommender_account_id, status, bounty_cents) "
                        + "VALUES (CAST(:id AS uuid), CAST(:task AS uuid), CAST(:rec AS uuid), 'pending', 0)")
                .bind("id", appId).bind("task", taskId).bind("rec", recommender).then().block();

        client().post().uri("/internal/marketplace/engagements/" + appId + "/creation-context")
                .header("X-Grassland-Identity", signService("intelligence"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("taskId", taskId, "recommenderAccountId", recommender))
                .exchange().expectStatus().isEqualTo(409);
    }

    private Fixture acceptedFixture() {
        String taskId = UUID.randomUUID().toString();
        String appId = UUID.randomUUID().toString();
        String merchant = UUID.randomUUID().toString();
        String organization = UUID.randomUUID().toString();
        String store = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        db.sql("INSERT INTO task(id, owner_account_id, organization_id, title, description, status,"
                        + " content_form, platform, store_id, version) VALUES (CAST(:id AS uuid), CAST(:owner AS uuid),"
                        + " CAST(:org AS uuid), '任务快照', '接受时描述', 'published', 'graphic',"
                        + " 'xiaohongshu', CAST(:store AS uuid), 1)")
                .bind("id", taskId).bind("owner", merchant).bind("org", organization).bind("store", store)
                .then().block();
        db.sql("INSERT INTO task_version(task_id, version, store_id, title, description, content_form, platform, requirements)"
                        + " VALUES (CAST(:task AS uuid), 1, CAST(:store AS uuid), '任务快照', '接受时描述',"
                        + " 'graphic', 'xiaohongshu', CAST(:requirements AS jsonb))")
                .bind("task", taskId).bind("store", store).bind("requirements", """
                        {"productServiceInfo":"双人招牌套餐","mustInclude":["门店名","招牌菜"],
                         "forbiddenContent":["绝对化功效"],"publishStartAt":"2026-08-20T10:00:00Z",
                         "publishEndAt":"2026-08-25T10:00:00Z","metricRequirements":["发布后 24 小时播放量"],
                         "evidenceRequirements":["发布链接","后台截图"]}
                        """)
                .then().block();
        db.sql("INSERT INTO task_application(id, task_id, recommender_account_id, status, bounty_cents) "
                        + "VALUES (CAST(:id AS uuid), CAST(:task AS uuid), CAST(:rec AS uuid), 'pending', 0)")
                .bind("id", appId).bind("task", taskId).bind("rec", recommender).then().block();
        db.sql("UPDATE task_application SET status='accepted', decided_at=now(),"
                        + " reputation_level_at_accept=1, reputation_policy_version_at_accept=1,"
                        + " settlement_delay_days_at_accept=2, commission_bonus_bps_at_accept=0,"
                        + " premium_support_at_accept=false WHERE id=CAST(:id AS uuid)")
                .bind("id", appId).then().block();
        return new Fixture(taskId, appId, organization, store, recommender);
    }

    private record Fixture(String taskId, String applicationId,
                           String organizationId, String storeId, String recommenderAccountId) {}
}
