package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * #26 满员自动关闭：在接受落定事务内判定并关闭，同事务发 {@code TaskClosed} 事件（D2/D11/D13）。
 *
 * <p>满员口径 = accepted 报名数 ≥ max_slots（D1，非 claim 占用数——reserving 预留失败会释放，按它判会误关）。
 * 判定实现 = {@link TaskRepository#closeIfFull}：task 行 FOR NO KEY UPDATE 前置锁（串行化并发激活的满员判定）
 * + 条件 UPDATE（D3）：无版本守卫，0 行 = 静默无操作。
 *
 * <p>{@link #closeIfFull} 只在调用方既有事务内使用（非资金型 accept、资金型 saga activateEngagement、revise）；
 * 关闭成功时同事务追加 {@code TaskClosed}（closeReason=slots_full），失败/未满向上游返回 empty，由调用方续走原逻辑。
 * 手动 close 路径（{@code TaskController}）复用 {@link #closedPayload}/{@link #taskClosedEnvelope}（closeReason=manual），
 * 保证两条路径 payload 键完全一致。
 */
@Component
public class TaskFullAutoCloser {

    private final TaskRepository tasks;
    private final OutboxRepository outbox;

    public TaskFullAutoCloser(TaskRepository tasks, OutboxRepository outbox) {
        this.tasks = tasks;
        this.outbox = outbox;
    }

    /** 只在调用方既有事务内使用：未满/无上限/已非 published → empty；关闭成功 → 返回关闭后任务（事件已追加）。 */
    public Mono<Task> closeIfFull(String taskId) {
        return tasks.closeIfFull(taskId)
                .flatMap(closed -> outbox.append(taskClosedEnvelope(closed, "slots_full"))
                        .thenReturn(closed));
    }

    /**
     * {@code TaskClosed} payload（手动/自动两条路径共用，键完全一致）：对齐 {@code TaskController#taskEventPayload}
     * 的键（taskId/organizationId/ownerAccountId/version[/storeId]），新增 {@code taskOwnerId}（identity 通知收件人
     * 解析，D11）与 {@code closeReason}（slots_full / manual，D13）。
     */
    public static Map<String, Object> closedPayload(Task task, String closeReason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.id());
        payload.put("organizationId", task.organizationId());
        payload.put("ownerAccountId", task.ownerAccountId());
        payload.put("taskOwnerId", task.ownerAccountId());
        payload.put("version", task.version());
        if (task.storeId() != null) {
            payload.put("storeId", task.storeId());
        }
        payload.put("closeReason", closeReason);
        return payload;
    }

    /** {@code TaskClosed} 事件信封（TaskController 手动 close 与本组件自动关闭共用）。 */
    static EventEnvelope taskClosedEnvelope(Task task, String closeReason) {
        return new EventEnvelope(UUID.randomUUID().toString(), "TaskClosed", "Task",
                task.id(), task.version(), Instant.now(), null, closedPayload(task, closeReason));
    }
}
