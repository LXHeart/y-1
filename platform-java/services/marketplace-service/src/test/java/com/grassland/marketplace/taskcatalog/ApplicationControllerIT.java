package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * application 聚合端到端（草场 Epic 4 Slice 4B）。继承 {@link MarketplaceItSupport}。
 *
 * <p>覆盖 apply / accept / reject / withdraw / list 全链路：身份门禁（recommender 报名、merchant 且 owner 接受）、
 * 名额控制（fail-fast at apply 基于 accepted 计数；accept 再校验）、去重（一人一报）、状态守卫、资源级自查、outbox 事件。
 * task 与 application 同库，org/account 用随机 UUID。
 */
class ApplicationControllerIT extends MarketplaceItSupport {

    private static final String ZERO_UUID = "00000000-0000-0000-0000-000000000000";

    // ---------- apply ----------

    @Test
    void recommenderAppliesAndEvent() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String rec = UUID.randomUUID().toString();

        client().post().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(rec, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "我能拍"))
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.data.taskId").isEqualTo(task)
                .jsonPath("$.data.recommenderAccountId").isEqualTo(rec)
                .jsonPath("$.data.status").isEqualTo("pending")
                .jsonPath("$.data.note").isEqualTo("我能拍");

        assertThat(outboxCount("ApplicationSubmitted", task)).isEqualTo(1);
    }

    @Test
    void applyWithoutAssertionUnauthorized() {
        String task = publishTask(UUID.randomUUID().toString(), UUID.randomUUID().toString(), null);
        client().post().uri("/api/tasks/" + task + "/applications")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void merchantCannotApply() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        client().post().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void applyUnknownTaskNotFound() {
        client().post().uri("/api/tasks/" + ZERO_UUID + "/applications")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void applyClosedTaskConflict() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        db.sql("UPDATE task SET status = 'closed' WHERE id = CAST(:id AS uuid)").bind("id", task).then().block();

        client().post().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void applyRejectedWhenSlotsFull() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, 1);  // 名额=1
        String appA = apply(UUID.randomUUID().toString(), task);
        accept(merchant, task, appA);  // accepted=1，名额满

        client().post().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isEqualTo(409);  // fail-fast
    }

    @Test
    void duplicateApplyConflict() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String rec = UUID.randomUUID().toString();
        apply(rec, task);  // 201
        client().post().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(rec, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isEqualTo(409);  // 已报名
    }

    // ---------- accept ----------

    @Test
    void merchantAcceptsAndEvent() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String app = apply(UUID.randomUUID().toString(), task);

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("accepted")
                .jsonPath("$.data.reviewedByAccountId").isEqualTo(merchant);

        assertThat(outboxCount("ApplicationAccepted", task)).isEqualTo(1);
        assertThat(acceptedCount(task)).isEqualTo(1);
    }

    @Test
    void acceptUnknownAppNotFound() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        client().post().uri("/api/tasks/" + task + "/applications/" + ZERO_UUID + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void acceptAlreadyProcessedConflict() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String app = apply(UUID.randomUUID().toString(), task);
        accept(merchant, task, app);  // 200
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void nonOwnerAcceptForbidden() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String app = apply(UUID.randomUUID().toString(), task);
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void recommenderCannotAccept() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String app = apply(UUID.randomUUID().toString(), task);
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void acceptBlockedWhenSlotsFull() {
        // 两份 pending 先报名（accepted=0 < 名额 1），再 accept 第一份占满，accept 第二份 → 409。
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, 1);
        String appA = apply(UUID.randomUUID().toString(), task);
        String appB = apply(UUID.randomUUID().toString(), task);
        accept(merchant, task, appA);  // 名额占满
        client().post().uri("/api/tasks/" + task + "/applications/" + appB + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isEqualTo(409);
    }

    // ---------- reject ----------

    @Test
    void merchantRejectsAndEvent() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String app = apply(UUID.randomUUID().toString(), task);
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/reject")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("rejected");
        assertThat(outboxCount("ApplicationRejected", task)).isEqualTo(1);
    }

    // ---------- list ----------

    @Test
    void ownerListsApplications() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        apply(UUID.randomUUID().toString(), task);
        apply(UUID.randomUUID().toString(), task);
        client().get().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").value(l -> assertThat((Integer) l).isEqualTo(2));
    }

    @Test
    void nonOwnerListForbidden() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        client().get().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant"))
                .exchange().expectStatus().isForbidden();
    }

    // ---------- withdraw ----------

    @Test
    void recommenderWithdrawsOwnPending() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String rec = UUID.randomUUID().toString();
        String app = apply(rec, task);
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/withdraw")
                .header("X-Grassland-Identity", sign(rec, "recommender"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("withdrawn");
        assertThat(outboxCount("ApplicationWithdrawn", task)).isEqualTo(1);
    }

    @Test
    void withdrawAcceptedConflict() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String rec = UUID.randomUUID().toString();
        String app = apply(rec, task);
        accept(merchant, task, app);
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/withdraw")
                .header("X-Grassland-Identity", sign(rec, "recommender"))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void withdrawOthersApplicationForbidden() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String appA = apply(UUID.randomUUID().toString(), task);
        client().post().uri("/api/tasks/" + task + "/applications/" + appA + "/withdraw")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isForbidden();
    }

    // ---------- helpers ----------

    private void accept(String merchant, String task, String app) {
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isOk();
    }

    @SuppressWarnings("unchecked")
    private String apply(String recommender, String task) {
        Map<String, Object> resp = client().post().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(recommender, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "申请"))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) resp.get("data")).get("id");
    }

    @SuppressWarnings("unchecked")
    private String publishTask(String merchant, String org, Integer maxSlots) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("organizationId", org);
        b.put("title", "任务");
        if (maxSlots != null) {
            b.put("maxSlots", maxSlots);
        }
        Map<String, Object> resp = client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(b)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) resp.get("data")).get("id");
    }

    private long outboxCount(String eventType, String taskId) {
        return db.sql("SELECT COUNT(*)::int AS c FROM marketplace_outbox"
                        + " WHERE event_type = :et AND payload->>'taskId' = :tid")
                .bind("et", eventType).bind("tid", taskId)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
    }

    private int acceptedCount(String taskId) {
        return db.sql("SELECT COUNT(*)::int AS c FROM task_application"
                        + " WHERE task_id = CAST(:tid AS uuid) AND status = 'accepted'")
                .bind("tid", taskId)
                .map(r -> r.get("c", Integer.class)).one().block();
    }
}
