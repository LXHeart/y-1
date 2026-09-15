package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.taskcatalog.EngagementExitRequestRepository.EngagementExitRequest;
import com.grassland.marketplace.taskcatalog.EngagementExtensionRepository.EngagementExtension;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 任务书 #103 C103-13：履约事件生产者契约（纯 JUnit，用真实信封工厂序列化）。
 *
 * <p>
 * §6.4 矩阵事件的 payload 必须携带 identity 通知中心解析收件人所需的真实事实：
 * {@code taskId/applicationId/taskOwnerId/recommenderAccountId}（+ 兜底/排除所需的
 * {@code organizationId/operatorAccountId}）与行内真实 ID/截止。系统动作（派发器/工作流） 的
 * {@code operatorAccountId} 为空；既有事件不重建载荷、确定性 eventId 不变。 已持久 outbox
 * 字节按原样重放（identity 侧按缺省兼容，见 EngagementNotificationContractTest）。
 */
class EngagementEventContractTest {

	private static final String TASK_ID = UUID.randomUUID().toString();
	private static final String APP_ID = UUID.randomUUID().toString();
	private static final String OWNER = UUID.randomUUID().toString();
	private static final String REC = UUID.randomUUID().toString();
	private static final String ORG = UUID.randomUUID().toString();

	private Task task() {
		return new Task(TASK_ID, OWNER, ORG, "任务标题", null, "published", "standard", "xiaohongshu", 1, 500_00L,
				Instant.now(), null, 1, null, null, null, 2, null, null, null, null, null, null, null, null, null, null,
				null, null);
	}

	private TaskApplication app() {
		return new TaskApplication(APP_ID, TASK_ID, REC, "accepted", null, null, null, Instant.now(), null, null,
				500_00L, null, null);
	}

	@Test
	@DisplayName("无责退出信封：M+R 双侧定位 + organizationId/operatorAccountId（O=操作推荐官）")
	void noFaultExitEnvelopeCarriesRecipientFacts() {
		EventEnvelope envelope = ApplicationEvents.noFaultExitEnvelope(app(), task(), REC);
		assertThat(envelope.eventType()).isEqualTo("ApplicationExitedNoFault");
		Map<String, Object> payload = envelope.payload();
		assertThat(payload.get("taskId")).isEqualTo(TASK_ID);
		assertThat(payload.get("applicationId")).isEqualTo(APP_ID);
		assertThat(payload.get("taskOwnerId")).isEqualTo(OWNER);
		assertThat(payload.get("recommenderAccountId")).isEqualTo(REC);
		assertThat(payload.get("organizationId")).isEqualTo(ORG);
		assertThat(payload.get("operatorAccountId")).isEqualTo(REC);
		assertThat(payload.get("exitedAt")).isNull(); // 未落 exitedAt 前由行守卫决定
		// 事件 ID 随机（状态机动作一次性），occurredAt 以信封时间戳为准（不为空）
		assertThat(envelope.occurredAt()).isNotNull();
	}

	@Test
	@DisplayName("协商退出信封：真实 exitRequestId/respondDeadlineAt/initiatedRole + 组织与操作者")
	void exitEnvelopeCarriesExitRequestFacts() {
		EngagementExitRequest request = new EngagementExitRequest(UUID.randomUUID().toString(), APP_ID, TASK_ID, REC,
				"recommender", "档期冲突", "pending", Instant.now().plusSeconds(172800), null, null, Instant.now());
		EventEnvelope requested = ApplicationLifecycleService.exitEnvelope("EngagementExitRequested", task(), app(),
				request, null, REC);
		Map<String, Object> payload = requested.payload();
		assertThat(payload.get("exitRequestId")).isEqualTo(request.id());
		assertThat(payload.get("initiatedRole")).isEqualTo("recommender");
		assertThat(payload.get("respondDeadlineAt")).isEqualTo(request.respondDeadlineAt().toString());
		assertThat(payload.get("taskOwnerId")).isEqualTo(OWNER);
		assertThat(payload.get("recommenderAccountId")).isEqualTo(REC);
		assertThat(payload.get("organizationId")).isEqualTo(ORG);
		assertThat(payload.get("operatorAccountId")).isEqualTo(REC);
		// 确定性 eventId（type:exitId）——重试 exactly-once 不因补字段而漂移
		assertThat(requested.eventId()).isEqualTo(java.util.UUID
				.nameUUIDFromBytes(
						("EngagementExitRequested:" + request.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8))
				.toString());
	}

	@Test
	@DisplayName("延期信封：extensionId/days/批准时新截止 + 组织与操作者；系统动作 O 为空")
	void extensionEnvelopeCarriesExtensionFacts() {
		EngagementExtension extension = new EngagementExtension(UUID.randomUUID().toString(), APP_ID, REC, 3, "出差",
				"pending", null, null, Instant.now());
		EventEnvelope requested = ApplicationLifecycleService.extensionEnvelope("DeliveryExtensionRequested", task(),
				app(), extension, null, REC);
		Map<String, Object> payload = requested.payload();
		assertThat(payload.get("extensionId")).isEqualTo(extension.id());
		assertThat(payload.get("days")).isEqualTo(3);
		assertThat(payload.get("taskOwnerId")).isEqualTo(OWNER);
		assertThat(payload.get("organizationId")).isEqualTo(ORG);
		assertThat(payload.get("operatorAccountId")).isEqualTo(REC);
		assertThat(payload).doesNotContainKey("deliveryDeadlineAt"); // 未批准：不显示旧截止

		Instant newDeadline = Instant.now().plusSeconds(3 * 86400L);
		EventEnvelope approved = ApplicationLifecycleService.extensionEnvelope("DeliveryExtensionApproved", task(),
				app(), extension, newDeadline, OWNER);
		assertThat(approved.payload().get("deliveryDeadlineAt")).isEqualTo(newDeadline.toString());
		assertThat(approved.payload().get("operatorAccountId")).isEqualTo(OWNER);
	}

	@Test
	@DisplayName("提交信封（草稿/正式交付）：organizationId 进入 payload（M 兜底/去重所需）")
	void submissionEnvelopeCarriesOrganization() {
		EngagementSubmission submission = new EngagementSubmission(UUID.randomUUID().toString(), APP_ID, REC,
				"https://example.com/draft", null, "submitted", null, null, Instant.now(), null);
		EventEnvelope draft = ApplicationEvents.submissionEnvelope("DraftSubmitted", app(), submission, List.of(),
				OWNER, ORG);
		Map<String, Object> payload = draft.payload();
		assertThat(payload.get("submissionId")).isEqualTo(submission.id());
		assertThat(payload.get("taskOwnerId")).isEqualTo(OWNER);
		assertThat(payload.get("organizationId")).isEqualTo(ORG);
		assertThat(payload.get("recommenderAccountId")).isEqualTo(REC);
	}
}
