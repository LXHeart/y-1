package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 协商退出申请超时失效看门狗（任务书 #97 C97-03 / D97-04，照 C96-01 dispatcher 范式）： pending
 * 且响应窗（respond_deadline_at）已过 → expired，合作按原履约继续（不触发任何资金动作）。 与对方响应的竞态由
 * {@code respond} SQL 烧入的 {@code respond_deadline_at > now()} 单边胜出： 置 expired
 * 后响应自然 0 行；响应先落定则本扫描的 pending 过滤不再命中。事件外发确定性 eventId。
 */
@Component
@ConditionalOnProperty(name = "marketplace.engagement.exit-expire-enabled", havingValue = "true", matchIfMissing = true)
public class EngagementExitExpireDispatcher {
	private static final Logger log = LoggerFactory.getLogger(EngagementExitExpireDispatcher.class);

	private final EngagementExitRequestRepository exits;
	private final OutboxRepository outbox;
	private final TaskApplicationRepository apps;
	private final TaskRepository tasks;
	private final int batchSize;

	public EngagementExitExpireDispatcher(EngagementExitRequestRepository exits, OutboxRepository outbox,
			TaskApplicationRepository apps, TaskRepository tasks,
			@Value("${marketplace.engagement.exit-expire-batch-size:50}") int batchSize) {
		this.exits = exits;
		this.outbox = outbox;
		this.apps = apps;
		this.tasks = tasks;
		this.batchSize = Math.max(1, Math.min(batchSize, 200));
	}

	@Scheduled(fixedDelayString = "${marketplace.engagement.exit-expire-poll-ms:60000}")
	public void dispatch() {
		// C103-13 补双侧收件键：envelope 装载改为反应式组合（app/task 查询不 block——
		// IT 直调本方法在 reactor 线程上，sync block 会炸；调度线程上同样安全）。
		exits.expireOverdue(batchSize).concatMap(
				expired -> expiredEnvelope(expired).flatMap(envelope -> outbox.append(envelope).thenReturn(expired)))
				.collectList()
				.doOnNext(expiredList -> expiredList
						.forEach(expired -> log.info("negotiated exit request expired app={} request={}",
								expired.applicationId(), expired.id())))
				.block();
	}

	/**
	 * 任务书 #103 C103-13（§6.4）：EngagementExitExpired 是 M+R 双侧行——补齐
	 * taskOwnerId/recommenderAccountId/organizationId，identity 通知中心才能解析双侧收件人。
	 */
	/**
	 * 任务书 #103 C103-13（§6.4）：EngagementExitExpired 是 M+R 双侧行——补齐
	 * taskOwnerId/recommenderAccountId/organizationId，identity 通知中心才能解析双侧收件人。
	 * 反应式组装（app/task 查询不 block——IT 直调 dispatch 在 reactor 线程上，sync block 会炸）；
	 * 行缺失保留基础 payload，不因缺行阻塞分区。
	 */
	private Mono<EventEnvelope> expiredEnvelope(EngagementExitRequestRepository.EngagementExitRequest request) {
		Mono<Map<String, Object>> enriched = apps.findById(request.applicationId())
				.flatMap(app -> tasks.findById(request.taskId())
						.map(task -> Map.of("recommenderAccountId", (Object) app.recommenderAccountId(), "taskOwnerId",
								(Object) task.ownerAccountId(), "organizationId", (Object) task.organizationId())))
				.defaultIfEmpty(Map.of());
		return enriched.map(extra -> {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("taskId", request.taskId());
			payload.put("applicationId", request.applicationId());
			payload.put("exitRequestId", request.id());
			payload.put("initiatedRole", request.initiatedRole());
			payload.put("status", "expired");
			payload.put("respondDeadlineAt", request.respondDeadlineAt().toString());
			payload.putAll(extra);
			String eventId = UUID
					.nameUUIDFromBytes(("EngagementExitExpired:" + request.id()).getBytes(StandardCharsets.UTF_8))
					.toString();
			return new EventEnvelope(eventId, "EngagementExitExpired", "TaskApplication", request.applicationId(), 1,
					Instant.now(), null, payload);
		});
	}

}
