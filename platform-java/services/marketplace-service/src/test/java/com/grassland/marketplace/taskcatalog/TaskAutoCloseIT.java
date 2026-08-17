package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * #26 满员自动关闭：名额 accept 落定即原子关闭（设计 D1–D3）。
 *
 * <p>非资金型接受链路（claimAcceptance）在「接受落定」的同一事务内判定 accepted 计数 ≥ max_slots，
 * 命中即 published→closed 并同事务追加 {@code TaskClosed}（closeReason=slots_full）事件。
 * outbox 发布器关闭（{@code marketplace.outbox.enabled=false}），直接查 marketplace_outbox 表断言。
 */
class TaskAutoCloseIT extends MarketplaceItSupport {

    // 场景 1：maxSlots=1，唯一报名接受成功 → 任务 closed + outbox TaskClosed(slots_full)。
    @Test
    void singleAcceptReachingCapClosesTaskAtomically() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, 1);
        String app = apply(UUID.randomUUID().toString(), task);

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("accepted");

        assertThat(taskStatus(task)).isEqualTo("closed");
        assertThat(outboxCount("TaskClosed", task)).isEqualTo(1);
        assertThat(outboxPayloadField("TaskClosed", task, "closeReason")).isEqualTo("slots_full");
        assertThat(outboxPayloadField("TaskClosed", task, "taskOwnerId")).isEqualTo(merchant);
        assertThat(outboxPayloadField("TaskClosed", task, "ownerAccountId")).isEqualTo(merchant);
    }

    // 场景 2：maxSlots=2，接受 1 个 → 仍 published，无 TaskClosed 事件。
    @Test
    void acceptBelowCapKeepsTaskPublished() {
        String merchant = UUID.randomUUID().toString();
        String task = publishTask(merchant, UUID.randomUUID().toString(), 2);
        String app = apply(UUID.randomUUID().toString(), task);

        accept(merchant, task, app);

        assertThat(acceptedCount(task)).isEqualTo(1);
        assertThat(taskStatus(task)).isEqualTo("published");
        assertThat(outboxCount("TaskClosed", task)).isZero();
    }

    // 场景 3：maxSlots=null（无上限）→ 接受后永不自动关闭。
    @Test
    void unlimitedTaskNeverAutoCloses() {
        String merchant = UUID.randomUUID().toString();
        String task = publishTask(merchant, UUID.randomUUID().toString(), null);
        String app = apply(UUID.randomUUID().toString(), task);

        accept(merchant, task, app);

        assertThat(acceptedCount(task)).isEqualTo(1);
        assertThat(taskStatus(task)).isEqualTo("published");
        assertThat(outboxCount("TaskClosed", task)).isZero();
    }

    // 场景 4：两并发接受抢最后一个名额，只成功一个，且成功者触发恰好一次关闭。
    @Test
    void concurrentFinalSlotAcceptClosesTaskExactlyOnce() throws Exception {
        String merchant = UUID.randomUUID().toString();
        String task = publishTask(merchant, UUID.randomUUID().toString(), 2);
        String first = apply(UUID.randomUUID().toString(), task);
        accept(merchant, task, first);  // 名额 2 已占 1，剩最后 1 个
        String second = apply(UUID.randomUUID().toString(), task);
        String third = apply(UUID.randomUUID().toString(), task);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Integer> secondStatus = new AtomicReference<>();
        AtomicReference<Integer> thirdStatus = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread secondThread = new Thread(() -> runConcurrentUpdate(ready, start, failure,
                () -> secondStatus.set(acceptStatus(merchant, task, second, "auto-close-2-" + UUID.randomUUID()))));
        Thread thirdThread = new Thread(() -> runConcurrentUpdate(ready, start, failure,
                () -> thirdStatus.set(acceptStatus(merchant, task, third, "auto-close-3-" + UUID.randomUUID()))));
        secondThread.start();
        thirdThread.start();
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        secondThread.join(10_000);
        thirdThread.join(10_000);

        assertThat(failure.get()).isNull();
        // 恰一个 200（抢到最后名额）、一个 409（名额已满）
        assertThat(List.of(secondStatus.get(), thirdStatus.get())).containsExactlyInAnyOrder(200, 409);
        assertThat(acceptedCount(task)).isEqualTo(2);
        // 胜者接受落定即关闭：任务 closed，TaskClosed(slots_full) 恰 1 条（败者事务回滚，不重复关闭）
        assertThat(taskStatus(task)).isEqualTo("closed");
        assertThat(outboxCount("TaskClosed", task)).isEqualTo(1);
        assertThat(outboxPayloadField("TaskClosed", task, "closeReason")).isEqualTo("slots_full");
    }

    // ---------- 造数/断言 helper（照 ApplicationControllerIT 的同名 helper 风格） ----------

    @SuppressWarnings("unchecked")
    private String publishTask(String merchant, String org, Integer maxSlots) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("organizationId", org);
        b.put("title", "满员关闭任务");
        if (maxSlots != null) {
            b.put("maxSlots", maxSlots);
        }
        Map<String, Object> resp = client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(b)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String taskId = (String) ((Map<String, Object>) resp.get("data")).get("id");
        markPublished(taskId);
        return taskId;
    }

    private void markPublished(String taskId) {
        db.sql("UPDATE task SET status = 'published', published_at = COALESCE(published_at, now()) "
                        + "WHERE id = CAST(:id AS uuid)")
                .bind("id", taskId).then().block();
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

    private void accept(String merchant, String task, String app) {
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isOk();
    }

    private int acceptStatus(String merchant, String task, String app, String idempotencyKey) {
        return client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .header("Idempotency-Key", idempotencyKey)
                .exchange().returnResult(Void.class).getStatus().value();
    }

    private void runConcurrentUpdate(CountDownLatch ready, CountDownLatch start,
                                     AtomicReference<Throwable> failure, Runnable update) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent update start timed out");
            }
            update.run();
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

    private String taskStatus(String taskId) {
        return db.sql("SELECT status FROM task WHERE id = CAST(:id AS uuid)")
                .bind("id", taskId)
                .map(r -> r.get("status", String.class)).one().block();
    }

    private int acceptedCount(String taskId) {
        return db.sql("SELECT COUNT(*)::int AS c FROM task_application"
                        + " WHERE task_id = CAST(:tid AS uuid) AND status = 'accepted'")
                .bind("tid", taskId)
                .map(r -> r.get("c", Integer.class)).one().block();
    }

    private long outboxCount(String eventType, String taskId) {
        return db.sql("SELECT COUNT(*)::int AS c FROM marketplace_outbox"
                        + " WHERE event_type = :et AND payload->>'taskId' = :tid")
                .bind("et", eventType).bind("tid", taskId)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
    }

    /** 读取按 taskId 限定的 TaskClosed 事件 payload 顶层字段（closeReason/taskOwnerId/ownerAccountId 断言）。 */
    private String outboxPayloadField(String eventType, String taskId, String field) {
        return db.sql("SELECT payload->>'" + field + "' AS v FROM marketplace_outbox"
                        + " WHERE event_type = :et AND payload->>'taskId' = :tid")
                .bind("et", eventType).bind("tid", taskId)
                .map(r -> r.get("v", String.class)).one().block();
    }
}
