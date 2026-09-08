package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.milestone.EngagementMilestoneService;
import com.grassland.marketplace.workflow.saga.SettlementWindowPolicy;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

@SuppressWarnings("unchecked")
class TaskPreviewIT extends MarketplaceItSupport {
    private static final String H = "X-Grassland-Identity";

    @Autowired private TaskRepository tasks;
    @Autowired private EngagementMilestoneService milestones;
    @Autowired private EngagementDeliveryPolicy deliveryPolicy;

    @Test
    void draftPreviewUsesPersistedContractAndDoesNotPublish() {
        Fixture fixture = draft(Map.of("bountyCents", 12345, "reviewRequired", true,
                "deliveryDeadlineDays", 5, "cancelPolicy", Map.of("script", 3000)));
        int versionBeforePreview = tasks.findById(fixture.id()).block().version();
        Map<String, Object> preview = preview(fixture);
        assertThat(section(preview, "payout")).containsEntry("estimatedPayoutCents", 12345);
        assertThat(section(preview, "delivery")).containsEntry("deliveryDeadlineDays", 5).containsEntry("source", "contract");
        assertThat(section(preview, "review")).containsEntry("required", true).containsEntry("reviewWindowHours", 2)
                .containsEntry("reviseCap", 2).containsEntry("resubmitHours", 1);
        assertThat(section(preview, "cancel")).containsEntry("scriptBps", 3000)
                .containsEntry("deliverableBps", 6000).containsEntry("publishedBps", 2000);
        Task persisted = tasks.findById(fixture.id()).block();
        assertThat(persisted.status()).isEqualTo("draft");
        assertThat(persisted.version()).isEqualTo(versionBeforePreview);
        assertThat(persisted.publishedAt()).isNull();
        assertThat(milestones.effectiveCancelBps(persisted).block().scriptBps()).isEqualTo(3000);
        assertThat(deliveryPolicy.contractFor(persisted).deliverySeconds()).isEqualTo(5 * 86400L);
    }

    @Test
    void fixedDepositAndLadderTemplatesUseSettlementAmounts() {
        Map<String, Object> fixed = preview(draft(Map.of("bountyCents", 10000)));
        assertThat(section(fixed, "payout")).containsEntry("mode", "bounty").containsEntry("estimatedPayoutCents", 10000);
        assertThat(section(fixed, "delivery")).containsEntry("deliveryDeadlineDays", 1).containsEntry("source", "default");

        Map<String, Object> freebie = preview(draft(Map.of("freebieDepositCents", 3000)));
        assertThat(section(freebie, "payout")).containsEntry("mode", "freebie").containsEntry("estimatedPayoutCents", 3000);
        assertThat(section(freebie, "cancel").get("cap").toString()).contains("押金全退");

        CommissionLadder ladder = new CommissionLadder("preview-v1", "views",
                List.of(new CommissionLadder.Tier(100, 2000), new CommissionLadder.Tier(1000, 8000)));
        Map<String, Object> tiered = preview(draft(Map.of("bountyCents", 10000,
                "requirements", Map.of("commissionLadder", ladder))));
        assertThat(section(tiered, "payout")).containsEntry("mode", "ladder").containsEntry("estimatedPayoutCents", null);
        long maximum = ((Number) section(tiered, "payout").get("maximumPayoutCents")).longValue();
        assertThat(maximum).isEqualTo(CommissionSettlementPlan.evaluate(ladder, 1000, 10000).settlementAmountCents());
        assertThat(maximum).isEqualTo(8000);
    }

    @Test
    void withdrawalPreviewHonorsProtectionFloorAndZeroDuration() {
        Fixture fixture = draft(Map.of("bountyCents", 10000));
        Task task = tasks.findById(fixture.id()).block();
        TaskPreviewService standard = new TaskPreviewService(milestones, deliveryPolicy, 86400, 172800, 72, 2, 48);
        Map<String, Object> payout = section(standard.preview(task).block(), "payout");
        assertThat(payout.get("withdrawableAfterConfirmSeconds")).isEqualTo(172800L);
        assertThat(SettlementWindowPolicy.windowSeconds(1, 86400, 172800)).isEqualTo(172800L);
        assertThat(payout.get("withdrawablePolicy").toString()).contains("至少 2 天").doesNotContain("天内");
        TaskPreviewService longerProtection = new TaskPreviewService(milestones, deliveryPolicy, 86400, 259200, 72, 2, 48);
        assertThat(section(longerProtection.preview(task).block(), "payout").get("withdrawableAfterConfirmSeconds"))
                .isEqualTo(SettlementWindowPolicy.windowSeconds((Integer) null, 86400, 259200));
        TaskPreviewService immediate = new TaskPreviewService(milestones, deliveryPolicy, 0, 0, 72, 2, 48);
        assertThat(section(immediate.preview(task).block(), "payout").get("withdrawablePolicy").toString()).contains("至少 0 天");
    }

    @Test
    void previewKeepsPrivateTasksAndLevelGatesPrivate() {
        Fixture fixture = draft(Map.of("bountyCents", 10000));
        stripStoreScope(fixture.id());
        String path = "/api/tasks/" + fixture.id() + "/preview";
        client().get().uri(path).exchange().expectStatus().isUnauthorized();
        String other = UUID.randomUUID().toString();
        client().get().uri(path).header(H, sign(other, "merchant", UUID.randomUUID().toString(), "basic_publish"))
                .exchange().expectStatus().isNotFound();
        db.sql("UPDATE task SET status = 'published', min_recommender_level = 5 WHERE id = CAST(:id AS uuid)")
                .bind("id", fixture.id()).then().block();
        client().get().uri(path).header(H, sign(other, "recommender")).exchange().expectStatus().isNotFound();
        db.sql("UPDATE task SET min_recommender_level = 1 WHERE id = CAST(:id AS uuid)")
                .bind("id", fixture.id()).then().block();
        client().get().uri(path).header(H, sign(other, "recommender")).exchange().expectStatus().isOk();
    }

    @Test
    void commercePreviewDoesNotPromiseBountyOrConfirmationSettlement() {
        Task task = new Task(UUID.randomUUID().toString(), "merchant", "org", "套餐推广", null,
                "draft", "image", "xiaohongshu", 1, 0L, Instant.now(), Instant.now(), 1,
                null, null, null, 1, null, TaskRequirements.empty(), null, 0L, null, null, null,
                TaskQuestion.none(), "package-1");
        TaskPreviewService service = new TaskPreviewService(milestones, deliveryPolicy, 86400, 172800, 72, 2, 48);
        Map<String, Object> data = service.preview(task).block();
        assertThat(section(data, "payout")).containsEntry("mode", "commerce")
                .containsEntry("estimatedPayoutCents", null).containsEntry("withdrawableAfterConfirmSeconds", null);
        assertThat(section(data, "payout").get("withdrawablePolicy").toString()).contains("订单核销").doesNotContain("确认后");
        assertThat(section(data, "cancel").get("cap").toString()).contains("既有订单佣金");
    }

    private Fixture draft(Map<String, Object> fields) {
        String owner = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> body = new HashMap<>(fields);
        body.put("organizationId", org);
        body.put("storeId", UUID.randomUUID().toString());
        body.put("title", "合作条款预览测试");
        body.put("platform", "xiaohongshu");
        body.put("contentForm", "image");
        body.put("applicationDeadline", Instant.now().plusSeconds(86400).toString());
        Map<String, Object> result = client().post().uri("/api/tasks/draft")
                .header(H, sign(owner, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return new Fixture((String) section(result, "data").get("id"), owner, org);
    }

    private Map<String, Object> preview(Fixture fixture) {
        Map<String, Object> response = client().get().uri("/api/tasks/" + fixture.id() + "/preview")
                .header(H, sign(fixture.owner(), "merchant", fixture.org(), "basic_publish"))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        return section(response, "data");
    }

    private static Map<String, Object> section(Map<String, Object> value, String key) {
        return (Map<String, Object>) value.get(key);
    }

    private record Fixture(String id, String owner, String org) { }
}
