package com.grassland.identity.invitation;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 邀请二次提醒扫描器（任务书 #41 尾条）：{@link InvitationReminderMonitor} 直接调
 * {@code processBatch}（同包可见，与 PermissionSlaMonitor 的 IT 验证方式一致）。
 *
 * <p>
 * 锁定：满阈值 pending 补发一次 MembershipInvitationReminder（claim 与事件同事务）； 重复扫描 0
 * 行幂等；新邀请（未满阈值）/已过期/已接受均不提醒。
 */
class InvitationReminderIT extends IdentityItSupport {

	@Autowired
	InvitationReminderMonitor monitor;

	@Test
	void agedPendingInvitationGetsExactlyOneReminder() {
		var owner = seedAccount("rem-owner@example.com");
		seedAccount("rem-invitee@example.com");
		String orgId = createOrg(owner.cookie(), "提醒主体");
		invite(orgId, owner.cookie(), "rem-invitee@example.com", "member").expectStatus().isCreated();
		String invitationId = lastInvitationId(orgId, owner.cookie());
		ageInvitation(invitationId, "3 days");

		monitor.processBatch(100).then().block();

		assertThat(reminderEvents(invitationId)).isEqualTo(1);
		assertThat(reminderSent(invitationId)).isTrue();

		// 幂等：二次扫描 claim 0 行，不再补发
		monitor.processBatch(100).then().block();
		assertThat(reminderEvents(invitationId)).isEqualTo(1);
	}

	@Test
	void freshExpiredAndAcceptedInvitationsAreNotReminded() {
		var owner = seedAccount("rem-owner2@example.com");
		String orgId = createOrg(owner.cookie(), "提醒排除主体");

		// 新邀请（未满 48h 阈值）
		invite(orgId, owner.cookie(), "rem-fresh@example.com", "member").expectStatus().isCreated();
		String fresh = lastInvitationId(orgId, owner.cookie());

		// 已过期的老邀请
		invite(orgId, owner.cookie(), "rem-expired@example.com", "member").expectStatus().isCreated();
		String expired = lastInvitationId(orgId, owner.cookie());
		ageInvitation(expired, "30 days");
		db.sql("UPDATE organization_invitation SET expires_at = now() - interval '1 hour'"
				+ " WHERE id = CAST(:id AS uuid)").bind("id", expired).then().block();

		// 已接受的老邀请
		var invitee = seedAccount("rem-accepted@example.com");
		invite(orgId, owner.cookie(), "rem-accepted@example.com", "member").expectStatus().isCreated();
		String accepted = lastInvitationId(orgId, owner.cookie());
		client().post().uri("/api/me/invitations/" + accepted + "/accept")
				.header("Cookie", "y1.sid=" + invitee.cookie()).exchange().expectStatus().isOk();
		ageInvitation(accepted, "3 days");

		monitor.processBatch(100).then().block();

		assertThat(reminderSent(fresh)).isFalse();
		assertThat(reminderSent(expired)).isFalse();
		assertThat(reminderSent(accepted)).isFalse();
		assertThat(reminderEvents(fresh)).isZero();
	}

	// ---------- helpers ----------

	private void ageInvitation(String invitationId, String age) {
		db.sql("UPDATE organization_invitation SET created_at = now() - interval '" + age + "'"
				+ " WHERE id = CAST(:id AS uuid)").bind("id", invitationId).then().block();
	}

	private boolean reminderSent(String invitationId) {
		Boolean sent = db
				.sql("SELECT (reminder_sent_at IS NOT NULL) AS sent FROM organization_invitation"
						+ " WHERE id = CAST(:id AS uuid)")
				.bind("id", invitationId).map(r -> r.get("sent", Boolean.class)).one().block();
		return Boolean.TRUE.equals(sent);
	}

	private long reminderEvents(String invitationId) {
		Long count = db
				.sql("SELECT COUNT(*)::bigint AS c FROM outbox"
						+ " WHERE event_type = 'MembershipInvitationReminder' AND aggregate_id = :agg")
				.bind("agg", invitationId).map(r -> r.get("c", Long.class)).one().block();
		return count == null ? 0 : count;
	}

	@SuppressWarnings("unchecked")
	private String lastInvitationId(String orgId, String cookie) {
		Map<String, Object> body = client().get().uri("/api/organizations/" + orgId + "/invitations")
				.header("Cookie", "y1.sid=" + cookie).exchange().expectStatus().isOk().expectBody(Map.class)
				.returnResult().getResponseBody();
		List<Map<String, Object>> list = (List<Map<String, Object>>) body.get("data");
		return (String) list.get(0).get("id");
	}

	private WebTestClient.ResponseSpec invite(String orgId, String cookie, String email, String role) {
		return client().post().uri("/api/organizations/" + orgId + "/invitations")
				.contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
				.bodyValue(Map.of("email", email, "role", role)).exchange();
	}
}
