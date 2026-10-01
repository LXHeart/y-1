package com.grassland.intelligence.hypit.fix2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.hypit.studio.HypitSessionRepository;
import com.grassland.intelligence.hypit.studio.HypitStudioSessionService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * C107F2-20（107-fix-2 / F19）：Studio 票据、只读、版本复用与撤销生命周期。
 *
 * <p>
 * 真 PostgreSQL + WireMock sidecar（studio.session/revoke/close 命令桩）。
 *
 * <ul>
 * <li>TC-F2-20-01 复用键 owner/project/kind/run/revision/readOnly 全等才复用；rev2
 * readonly 打开新会话且 readOnly=true、reused=false。</li>
 * <li>TC-F2-20-02 同键重开 reused=true 并换发新票（nonceHash 槽被替换）；旧票重放拒绝、 新票 CAS
 * 核销一次。</li>
 * <li>TC-F2-20-03 关闭幂等：closed 后 PG 终态，再关仍 closed 语义（HTTP 层 200 由 controller
 * 保证）；close 通知 sidecar（studio.session.close 桩收到）。</li>
 * <li>TC-F2-20-04 撤销矩阵：revokeAllForOwner 置 revoked + revoked_at；过期访问惰性转
 * expired（markExpired）。</li>
 * </ul>
 */
@TestPropertySource(properties = "hypit.enabled=true")
class HypitFix2C20IT extends IntelligenceItSupport {

	private static final String OWNER = "eeeeeeee-0000-4000-8000-00000000200a";
	private static final WireMockServer SIDECAR = new WireMockServer(0);

	@Autowired
	HypitStudioSessionService sessions;

	@Autowired
	HypitSessionRepository repository;

	@Autowired
	DatabaseClient db;

	private UUID projectId;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("hypit.internal-token", () -> "fix2-c20-internal-token-0123456789abcdef");
		registry.add("hypit.sidecar-base-url", SIDECAR::baseUrl);
		registry.add("hypit.session-ticket-secret", () -> "fix2-c20-ticket-secret-0123456789abcdef");
		registry.add("hypit.session-assertion-secret", () -> "fix2-c20-assert-secret-0123456789abcdef");
	}

	@BeforeAll
	static void startSidecar() {
		SIDECAR.start();
	}

	@AfterAll
	static void stopSidecar() {
		SIDECAR.stop();
	}

	@BeforeEach
	void seed() {
		SIDECAR.resetRequests();
		cleanup();
		projectId = UUID.randomUUID();
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("studio.session"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"c\",\"state\":\"succeeded\",\"result\":{\"sessionId\":\"sid\","
								+ "\"port\":9500,\"pid\":1,\"expiresAt\":\"2026-12-31T00:00:00Z\","
								+ "\"revision\":1,\"readOnly\":false}}")));
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'fix2-c20', 'clone', 'ready', 1)")
				.bind("id", projectId.toString()).bind("owner", OWNER).bind("ws", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(10));
	}

	@AfterEach
	void sweep() {
		cleanup();
	}

	private void cleanup() {
		db.sql("DELETE FROM hypit_session WHERE account_id = :o").bind("o", OWNER).then()
				.then(db.sql("DELETE FROM hypit_command WHERE account_id = :o").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id = :o").bind("o", OWNER).then())
				.block(Duration.ofSeconds(20));
	}

	// ── TC-F2-20-01：复用键——rev/readOnly 变化一律新会话 ─────────────────────
	@Test
	@DisplayName("TC-F2-20-01 rev2 readonly 打开不同会话：readOnly=true、reused=false")
	void reuseKeyVariantOpensDifferentSession() {
		HypitStudioSessionService.StudioSessionView first = sessions
				.openSession(OWNER, projectId, UUID.randomUUID(), "main.svrun", 1L, false)
				.block(Duration.ofSeconds(30));
		assertThat(first).isNotNull();
		assertThat(first.reused()).isFalse();
		assertThat(first.readOnly()).isFalse();
		assertThat(first.revision()).isEqualTo(1L);

		HypitStudioSessionService.StudioSessionView variant = sessions
				.openSession(OWNER, projectId, UUID.randomUUID(), "main.svrun", 2L, true).block(Duration.ofSeconds(30));
		assertThat(variant).isNotNull();
		assertThat(variant.sessionId()).as("rev/readOnly 变化必须是新会话").isNotEqualTo(first.sessionId());
		assertThat(variant.readOnly()).isTrue();
		assertThat(variant.revision()).isEqualTo(2L);
		assertThat(variant.reused()).isFalse();

		// 同键第二次打开：复用（reused=true，同 sessionId）。
		HypitStudioSessionService.StudioSessionView reused = sessions
				.openSession(OWNER, projectId, UUID.randomUUID(), "main.svrun", 1L, false)
				.block(Duration.ofSeconds(30));
		assertThat(reused).isNotNull();
		assertThat(reused.sessionId()).isEqualTo(first.sessionId());
		assertThat(reused.reused()).isTrue();
	}

	// ── TC-F2-20-02：同会话换发新票；nonce 单槽替换；新票 CAS 核销一次 ────────
	@Test
	@DisplayName("TC-F2-20-02 复用换新票：nonceHash 槽被替换，新票可核销一次")
	void reuseReissuesTicketReplacingNonceSlot() {
		HypitStudioSessionService.StudioSessionView first = sessions
				.openSession(OWNER, projectId, UUID.randomUUID(), "main.svrun", 1L, false)
				.block(Duration.ofSeconds(30));
		assertThat(first).isNotNull();
		String firstHash = ticketNonceHash(first.sessionId());
		assertThat(firstHash).isNotBlank();

		HypitStudioSessionService.StudioSessionView reopened = sessions
				.openSession(OWNER, projectId, UUID.randomUUID(), "main.svrun", 1L, false)
				.block(Duration.ofSeconds(30));
		assertThat(reopened).isNotNull();
		assertThat(reopened.sessionId()).isEqualTo(first.sessionId());
		assertThat(reopened.reused()).isTrue();
		assertThat(reopened.messageNonce()).as("每次签发独立 messageNonce").isNotBlank();
		String secondHash = ticketNonceHash(first.sessionId());
		assertThat(secondHash).as("nonce 单槽已被新票替换").isNotEqualTo(firstHash);

		// 从新票 URL 提取 nonce 并核销：CAS 一次成功；重放失败。
		String nonce = extractNonce(reopened.ticketUrl());
		String nonceHash = HypitStudioSessionService.sha256Hex(nonce);
		assertThat(repository.redeemTicket(first.sessionId(), nonceHash).block(Duration.ofSeconds(10))).as("新票首次核销成功")
				.isNotNull();
		assertThat(repository.redeemTicket(first.sessionId(), nonceHash).block(Duration.ofSeconds(10))).as("同票重放拒绝")
				.isNull();
	}

	// ── TC-F2-20-03：关闭幂等 + sidecar 通知 ─────────────────────────────────
	@Test
	@DisplayName("TC-F2-20-03 关闭置 closed 并通知 sidecar；未知会话幂等不抛")
	void closeIsIdempotentAndNotifiesBroker() {
		HypitStudioSessionService.StudioSessionView opened = sessions
				.openSession(OWNER, projectId, UUID.randomUUID(), "main.svrun", 1L, false)
				.block(Duration.ofSeconds(30));
		assertThat(opened).isNotNull();

		HypitStudioSessionService.StudioSessionView closed = sessions.closeSession(OWNER, projectId, opened.sessionId())
				.block(Duration.ofSeconds(30));
		assertThat(closed).isNotNull();
		String state = sessionState(opened.sessionId());
		assertThat(state).isEqualTo("closed");
		assertThat(SIDECAR.getAllServeEvents()).as("studio.session.close 通知已派发")
				.anySatisfy(event -> assertThat(event.getRequest().getBodyAsString()).contains("studio.session.close"));

		// 已终态再关：幂等（无 sidecar 断言需求，不再翻状态）。
		HypitStudioSessionService.StudioSessionView again = sessions.closeSession(OWNER, projectId, opened.sessionId())
				.block(Duration.ofSeconds(30));
		assertThat(again).isNotNull();
		assertThat(sessionState(opened.sessionId())).isEqualTo("closed");
	}

	// ── TC-F2-20-04：撤销矩阵 + 惰性过期 ─────────────────────────────────────
	@Test
	@DisplayName("TC-F2-20-04 revokeAllForOwner 置 revoked；markExpired 惰性转终态")
	void revocationAndLazyExpiry() {
		HypitStudioSessionService.StudioSessionView opened = sessions
				.openSession(OWNER, projectId, UUID.randomUUID(), "main.svrun", 1L, false)
				.block(Duration.ofSeconds(30));
		assertThat(opened).isNotNull();
		Long revoked = repository.revokeAllForOwner(OWNER, projectId).block(Duration.ofSeconds(10));
		assertThat(revoked).isEqualTo(1L);
		assertThat(sessionState(opened.sessionId())).isEqualTo("revoked");
		assertThat(sessionRevokedAt(opened.sessionId())).isNotNull();
		// 再撤：无活跃行，0。
		Long again = repository.revokeAllForOwner(OWNER, projectId).block(Duration.ofSeconds(10));
		assertThat(again).isZero();

		// 惰性过期：把 TTL 推到过去 → markExpired 收敛终态。
		HypitStudioSessionService.StudioSessionView stale = sessions
				.openSession(OWNER, projectId, UUID.randomUUID(), "main.svrun", 1L, false)
				.block(Duration.ofSeconds(30));
		assertThat(stale).isNotNull();
		db.sql("UPDATE hypit_session SET expires_at = now() - interval '1 minute' WHERE id = :id")
				.bind("id", stale.sessionId()).then().block(Duration.ofSeconds(10));
		repository.markExpired(stale.sessionId()).block(Duration.ofSeconds(10));
		assertThat(sessionState(stale.sessionId())).isEqualTo("expired");
	}

	private String ticketNonceHash(String sessionId) {
		String hash = db.sql("SELECT ticket_nonce_hash AS h FROM hypit_session WHERE id = :id").bind("id", sessionId)
				.map((row, meta) -> row.get("h", String.class)).one().block(Duration.ofSeconds(10));
		return hash == null ? "" : hash.trim();
	}

	private String sessionState(String sessionId) {
		String state = db.sql("SELECT state FROM hypit_session WHERE id = :id").bind("id", sessionId)
				.map((row, meta) -> row.get("state", String.class)).one().block(Duration.ofSeconds(10));
		return state == null ? "" : state;
	}

	private java.time.Instant sessionRevokedAt(String sessionId) {
		return db.sql("SELECT revoked_at FROM hypit_session WHERE id = :id").bind("id", sessionId)
				.map((row, meta) -> row.get("revoked_at", java.time.Instant.class)).one().block(Duration.ofSeconds(10));
	}

	/**
	 * ticketUrl=/studio/<sid>/?ticket=<sid>.<expMilli>.<nonce>.<hmac>，提取中间 nonce。
	 */
	private static String extractNonce(String ticketUrl) {
		int at = ticketUrl.indexOf("ticket=");
		String ticket = ticketUrl.substring(at + "ticket=".length());
		String[] parts = ticket.split("\\.");
		assertThat(parts).hasSize(4);
		return parts[2];
	}
}
