package com.grassland.identity.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.identity.IdentityItSupport;
import com.grassland.messaging.EventContractException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

/**
 * 通知消费链的数据库集成测试（Slice 12 Stage 2）。真实 {@link NotificationEventProcessor} + 真实
 * resolver/inbox/notification repo + 单例 Postgres（Flyway V11/V12 建表）。
 *
 * <p>
 * <b>共享容器数据累积</b>：单例容器跨 {@code @Test} 共享，故每个测试用<b>唯一 eventId + 唯一账号/邮箱</b>， 断言按
 * accountId（各自的收件箱）或按「同 eventId 重投的结果」判定，不依赖全局计数（见 ItSupport 注释）。
 *
 * <p>
 * 覆盖：① 邮箱→账号解析 + 通知落库；② 未注册邮箱→静默跳过但仍写 inbox（重投→DUPLICATE 证已记录）； ③ 重复
 * eventId→DUPLICATE；④ 同 ID 异 payload→契约冲突；⑤ org 扇出 owner/admin 排除操作者； ⑥
 * {@code PermissionReviewed} 经 merchant_permission_request 反查 requester；⑦
 * 通知插入失败→inbox 回滚（重投→PROCESSED 证回滚）。
 */
class NotificationInboxIT extends IdentityItSupport {

	@Autowired
	private NotificationEventProcessor processor;
	@Autowired
	private NotificationRepository notifications;
	@Autowired
	private DatabaseClient db;
	@Autowired
	private org.springframework.transaction.reactive.TransactionalOperator transactions;
	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void membershipInvitedResolvesRecipientByEmailAndInsertsNotification() {
		var invitee = seedAccount("inbox-invitee@example.com");
		ConsumerRecord<String, String> record = envelope("evt-A", "MembershipInvited", "inv-1",
				Map.of("email", "inbox-invitee@example.com", "organizationId", "org-1"));

		assertThat(processor.process(record).block()).isEqualTo(NotificationProcessingResult.PROCESSED);

		List<Notification> mine = notifications.findByAccount(invitee.accountId(), false, 10, null, null).collectList()
				.block();
		assertThat(mine).hasSize(1);
		assertThat(mine.get(0).eventType()).isEqualTo("MembershipInvited");
		assertThat(mine.get(0).category()).isEqualTo(NotificationCategory.INVITATION);
		assertThat(mine.get(0).sourceEventId()).isEqualTo("evt-A");
	}

	@Test
	void unknownEmailIsSkippedButInboxStillRecorded() {
		ConsumerRecord<String, String> record = envelope("evt-B", "MembershipInvited", "inv-2",
				Map.of("email", "not-registered-" + UUID.randomUUID() + "@example.com", "organizationId", "org-1"));

		// 首次：未注册 → 无通知但 PROCESSED（inbox 已记录）
		assertThat(processor.process(record).block()).isEqualTo(NotificationProcessingResult.PROCESSED);
		// 同 eventId 重投 → DUPLICATE，证 inbox 确实落了一行（否则会再次 PROCESSED）
		assertThat(processor.process(record).block()).isEqualTo(NotificationProcessingResult.DUPLICATE);
	}

	@Test
	void duplicateEventIdIsIdempotent() {
		ConsumerRecord<String, String> record = envelope("evt-C", "MembershipInvited", "inv-3",
				Map.of("email", "dup-" + UUID.randomUUID() + "@example.com", "organizationId", "org-1"));

		assertThat(processor.process(record).block()).isEqualTo(NotificationProcessingResult.PROCESSED);
		assertThat(processor.process(record).block()).isEqualTo(NotificationProcessingResult.DUPLICATE);
	}

	@Test
	void conflictingPayloadForSameEventIdThrows() {
		ConsumerRecord<String, String> first = envelope("evt-D", "MembershipInvited", "inv-4",
				Map.of("email", "a-" + UUID.randomUUID() + "@example.com", "organizationId", "org-1"));
		processor.process(first).block();

		// 同 eventId 但 payload 不同 → 契约冲突（不可重试 → DLT）
		ConsumerRecord<String, String> conflicting = envelope("evt-D", "MembershipInvited", "inv-4",
				Map.of("email", "b-" + UUID.randomUUID() + "@example.com", "organizationId", "org-1"));
		assertThatThrownBy(() -> processor.process(conflicting).block()).isInstanceOf(EventContractException.class)
				.hasMessageContaining("conflicting");
	}

	@Test
	void invitationAcceptedFansOutToOrgManagersExcludingActor() {
		var owner = seedAccount("inbox-owner@example.com");
		var admin = seedAccount("inbox-admin@example.com");
		var actor = seedAccount("inbox-actor@example.com");
		String orgId = UUID.randomUUID().toString();
		seedMember(orgId, owner.accountId(), "owner");
		seedMember(orgId, admin.accountId(), "admin");
		seedMember(orgId, actor.accountId(), "member");

		ConsumerRecord<String, String> record = envelope("evt-E", "MembershipInvitationAccepted", "inv-5",
				Map.of("organizationId", orgId, "accountId", actor.accountId(), "role", "member"));
		processor.process(record).block();

		assertThat(unreadFor(owner.accountId())).as("owner 被通知").isEqualTo(1);
		assertThat(unreadFor(admin.accountId())).as("admin 被通知").isEqualTo(1);
		assertThat(unreadFor(actor.accountId())).as("操作者本人不被通知").isZero();
	}

	@Test
	void budgetThresholdAlertNotifiesOrgManagersWithWalletTemplateAndMail() {
		var owner = seedAccount("budget-owner@example.com");
		var admin = seedAccount("budget-admin@example.com");
		var member = seedAccount("budget-member@example.com");
		String orgId = UUID.randomUUID().toString();
		seedMember(orgId, owner.accountId(), "owner");
		seedMember(orgId, admin.accountId(), "admin");
		seedMember(orgId, member.accountId(), "member");

		ConsumerRecord<String, String> record = envelope("evt-budget-1", "AiOrgBudgetThresholdCrossed", orgId,
				Map.of("organizationId", orgId, "ruleKey", "daily_cents", "level", "exceeded",
						"window", "daily", "unit", "cents", "periodKey", "2026-08-21",
						"usage", 105, "limit", 100));
		assertThat(processor.process(record).block()).isEqualTo(NotificationProcessingResult.PROCESSED);

		assertThat(unreadFor(owner.accountId())).as("owner 被通知").isEqualTo(1);
		assertThat(unreadFor(admin.accountId())).as("admin 被通知").isEqualTo(1);
		assertThat(unreadFor(member.accountId())).as("普通成员不接收预算告警").isZero();

		// WALLET 类属邮件高价值子集：owner/admin 各入队一封
		Long mailCount = db.sql("SELECT COUNT(*) FROM mail_outbox WHERE source_event_id = :eventId")
				.bind("eventId", "evt-budget-1").map(r -> r.get(0, Long.class)).one().block();
		assertThat(mailCount).isEqualTo(2);

		// 同 eventId 重投幂等（inbox 双闸）
		assertThat(processor.process(record).block()).isEqualTo(NotificationProcessingResult.DUPLICATE);
	}

	@Test
	void budgetThresholdAlertWithMalformedOrgIdYieldsNoRecipients() {
		ConsumerRecord<String, String> record = envelope("evt-budget-2", "AiOrgBudgetThresholdCrossed", "not-a-uuid",
				Map.of("organizationId", "not-a-uuid", "ruleKey", "daily_tokens", "level", "warning",
						"window", "daily", "unit", "tokens", "periodKey", "2026-08-21",
						"usage", 80, "limit", 100));
		// 非法组织 id 不抛错重试：PROCESSED + 零通知（防御，不阻塞分区）
		assertThat(processor.process(record).block()).isEqualTo(NotificationProcessingResult.PROCESSED);
	}

	@Test
	void permissionReviewedLooksUpRequesterByAggregateId() {
		var requester = seedAccount("inbox-requester@example.com");
		String orgId = UUID.randomUUID().toString();
		String requestId = db.sql("""
				INSERT INTO merchant_permission_request
				    (id, organization_id, requester_account_id, requested_tier, status, industry)
				VALUES (CAST(:id AS uuid), CAST(:org AS uuid), CAST(:acct AS uuid),
				        'finance_transaction', 'pending', '餐饮')
				RETURNING id::text
				""").bind("id", UUID.randomUUID().toString()).bind("org", orgId).bind("acct", requester.accountId())
				.map(row -> row.get("id", String.class)).one().block();

		// aggregateId = 权限申请 id；payload 只有 orgId+decision（无 requesterAccountId）→ 反查表
		ConsumerRecord<String, String> record = envelope("evt-F", "PermissionReviewed", requestId,
				Map.of("organizationId", orgId, "decision", "approved"));
		processor.process(record).block();

		assertThat(unreadFor(requester.accountId())).as("申请人被通知").isEqualTo(1);
	}

	@Test
	void permissionAdmissionEventsNotifyPlatformAdmins() {
		var requester = seedAccount("inbox-permission-requester@example.com");
		var orgManager = seedAccount("inbox-permission-org-admin@example.com");
		var platformAdmin = seedAdmin("inbox-permission-platform-admin@example.com");
		String orgId = UUID.randomUUID().toString();
		seedMember(orgId, requester.accountId(), "owner");
		seedMember(orgId, orgManager.accountId(), "admin");

		processor.process(envelope("evt-permission-requested", "PermissionRequested", UUID.randomUUID().toString(),
				Map.of("organizationId", orgId, "requesterAccountId", requester.accountId(), "requestedTier",
						"basic_publish")))
				.block();
		processor.process(envelope("evt-permission-sla", "PermissionReviewSlaBreached", UUID.randomUUID().toString(),
				Map.of("organizationId", orgId, "requestId", UUID.randomUUID().toString(), "requestedTier",
						"basic_publish")))
				.block();

		assertThat(unreadFor(platformAdmin.accountId())).as("平台管理员收到申请和 SLA 提醒").isEqualTo(2);
		assertThat(unreadFor(requester.accountId())).as("申请人不收到待审提醒").isZero();
		assertThat(unreadFor(orgManager.accountId())).as("组织管理员不是平台审核人").isZero();
	}

	@Test
	void notificationInsertFailureRollsBackInboxRow() {
		// 必须先注册账号，resolver 才会解析出收件人，从而走到被注入失败的 insertIfAbsent
		seedAccount("inbox-rollback@example.com");
		ConsumerRecord<String, String> record = envelope("evt-G", "MembershipInvited", "inv-6",
				Map.of("email", "inbox-rollback@example.com", "organizationId", "org-1"));

		// 同事务：通知插入恒失败 → inbox 行应随之回滚（不留半成品）
		var spy = org.mockito.Mockito.spy(notifications);
		doReturn(Mono.error(new RuntimeException("forced insert failure"))).when(spy).insertIfAbsent(anyString(), any(),
				anyString(), anyString(), any(), any(), eq("evt-G"), any());
		NotificationEventProcessor failingProcessor = new NotificationEventProcessor(
				new com.grassland.identity.event.InboxRepository(db),
				new com.grassland.identity.notification.NotificationRecipientResolver(db), spy,
				new com.grassland.identity.notify.mail.MailOutboxEnqueuer(
						new com.grassland.identity.notify.mail.MailOutboxRepository(db), db),
				new com.grassland.identity.notify.external.ExternalDeliveryEnqueuer(
						new com.grassland.identity.notify.external.ExternalDeliveryRepository(db)),
				transactions, "identity-notification-consumer");

		assertThatThrownBy(() -> failingProcessor.process(record).block()).isInstanceOf(RuntimeException.class)
				.hasMessageContaining("forced insert failure");

		// 回滚证明：同 eventId 用真实 processor 重投 → 仍 PROCESSED（若 inbox 行已提交则会是 DUPLICATE）
		assertThat(processor.process(record).block()).as("inbox 行已回滚，故可重新处理")
				.isEqualTo(NotificationProcessingResult.PROCESSED);
	}

	// ---- helpers ----

	private long unreadFor(String accountId) {
		Long c = notifications.countUnread(accountId).block();
		return c == null ? 0L : c;
	}

	/**
	 * 单例容器跨测试共享：每条记录取唯一 offset，避免触发 (consumer,topic,partition,offset) 唯一约束的 offset
	 * 复用保护。
	 */
	private static final java.util.concurrent.atomic.AtomicLong OFFSET = new java.util.concurrent.atomic.AtomicLong(1);

	private ConsumerRecord<String, String> envelope(String eventId, String eventType, String aggregateId,
			Map<String, Object> payload) {
		String json;
		try {
			json = mapper.writeValueAsString(Map.of("eventId", eventId, "eventType", eventType, "aggregateType",
					"Aggregate", "aggregateId", aggregateId, "payload", payload));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return new ConsumerRecord<>("grassland.identity.events", 0, OFFSET.getAndIncrement(), aggregateId, json);
	}

	private void seedMember(String orgId, String accountId, String role) {
		db.sql("""
				INSERT INTO organization_membership(id, organization_id, account_id, role)
				VALUES (CAST(:id AS uuid), CAST(:org AS uuid), CAST(:acct AS uuid), :role)
				""").bind("id", UUID.randomUUID().toString()).bind("org", orgId).bind("acct", accountId)
				.bind("role", role).then().block();
	}
}
