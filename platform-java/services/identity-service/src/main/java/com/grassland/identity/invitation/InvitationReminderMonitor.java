package com.grassland.identity.invitation;

import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;

/**
 * 邀请二次提醒扫描器（任务书 #41 尾巴，{@link PermissionSlaMonitor} 同款范式）：对 「pending + 未过期 +
 * 创建超过阈值 + 从未提醒」的邀请各补发一次 {@code MembershipInvitationReminder}
 * 事件（通知/邮件链路与首次邀请共用）。
 *
 * <p>
 * 幂等：{@code reminder_sent_at} 条件 UPDATE claim 与 outbox 追加同事务——重复扫描 0 行
 * 不进链；一封邀请终身最多一次提醒。过期邀请不提醒（读侧按 {@code expires_at} 判定过期， 不翻状态，与
 * {@link InvitationStatus} 语义一致）。
 */
@Component
@ConditionalOnProperty(prefix = "identity.invitation.reminder-monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InvitationReminderMonitor {

	private static final Logger log = LoggerFactory.getLogger(InvitationReminderMonitor.class);

	private final InvitationRepository invitations;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;
	private final AtomicBoolean polling = new AtomicBoolean();
	private final Duration reminderAfter;

	public InvitationReminderMonitor(InvitationRepository invitations, OutboxRepository outbox,
			TransactionalOperator transactions, org.springframework.core.env.Environment environment) {
		this.invitations = invitations;
		this.outbox = outbox;
		this.transactions = transactions;
		this.reminderAfter = Duration
				.ofHours(environment.getProperty("identity.invitation.reminder-after-hours", Long.class, 48L));
	}

	@Scheduled(fixedDelayString = "${identity.invitation.reminder-monitor.poll-interval-ms:600000}")
	public void poll() {
		if (!polling.compareAndSet(false, true))
			return;
		processBatch(100).doOnError(error -> log.error("invitation reminder monitor failed", error))
				.doFinally(signal -> polling.set(false)).subscribe();
	}

	Flux<Void> processBatch(int limit) {
		Instant createdBefore = Instant.now().minus(reminderAfter);
		return transactions.transactional(invitations.claimPendingReminders(createdBefore, limit)
				.concatMap(invitation -> outbox.append(reminderEvent(invitation)).then()));
	}

	private static EventEnvelope reminderEvent(Invitation invitation) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("invitationId", invitation.id());
		payload.put("organizationId", invitation.organizationId());
		if (invitation.storeId() != null)
			payload.put("storeId", invitation.storeId());
		payload.put("email", invitation.email());
		payload.put("role", invitation.role());
		payload.put("expiresAt", invitation.expiresAt().toString());
		return new EventEnvelope(UUID.randomUUID().toString(), "MembershipInvitationReminder", "OrganizationInvitation",
				invitation.id(), 1, Instant.now(), null, payload);
	}
}
