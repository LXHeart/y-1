package com.grassland.identity.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;

import com.grassland.identity.IdentityItSupport;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Mono;

/**
 * Slice 7C：证明 identity controller 写路径的「领域写 + outbox append」在同一 R2DBC 事务。
 *
 * <p>
 * 用 {@code @MockitoSpyBean} 把 {@link OutboxRepository#append} 针对某事件类型注入失败，
 * 断言领域写（组织 / 门店 / 子账号状态）随之回滚。覆盖 create（switchIfEmpty 内单写）+ store 两种写形态； 其余
 * controller（membership/identityProfile/permission/register）同形态 （均
 * {@code transactions.transactional(写+outbox)}），由既有 IT 守 happy path。
 * 任务书 #49：邀请 accept 场景随邀请流下线移除，换成 #48 子账号停用的同事务原子性。
 */
class OutboxAtomicityIT extends IdentityItSupport {

	@MockitoSpyBean
	OutboxRepository outbox;

	@Test
	void createOrgRollsBackWhenOutboxFails() {
		Seeded owner = seedAccount("org-fail-" + UUID.randomUUID() + "@example.com");

		failOutboxOn("OrganizationCreated");
		client().post().uri("/api/organizations").contentType(MediaType.APPLICATION_JSON)
				.header("Cookie", "y1.sid=" + owner.cookie()).bodyValue("{\"name\":\"X\"}").exchange().expectStatus()
				.is5xxServerError();

		assertThat(orgCountByOwner(owner.accountId())).isZero(); // 组织未建
	}

	@Test
	void createStoreRollsBackWhenOutboxFails() {
		Seeded owner = seedAccount("store-fail-" + UUID.randomUUID() + "@example.com");
		String orgId = createOrg(owner.cookie(), "X"); // outbox 正常

		failOutboxOn("StoreCreated");
		client().post().uri("/api/organizations/" + orgId + "/stores").contentType(MediaType.APPLICATION_JSON)
				.header("Cookie", "y1.sid=" + owner.cookie()).bodyValue("{\"name\":\"S\"}").exchange().expectStatus()
				.is5xxServerError();

		assertThat(storeCountByOrg(orgId)).isZero(); // 门店未建
	}

	@Test
	void suspendSubAccountRollsBackWhenOutboxFails() {
		var owner = seedAccount("atom-owner-" + UUID.randomUUID() + "@example.com");
		String orgId = createOrg(owner.cookie(), "X");
		String accountId = createSubAccount(orgId, owner.cookie());

		failOutboxOn("MemberSuspensionChanged");
		client().post().uri("/api/organizations/" + orgId + "/accounts/" + accountId + "/suspend")
				.header("Cookie", "y1.sid=" + owner.cookie()).exchange().expectStatus().is5xxServerError();

		// 复核轮修正 4：状态变更与 outbox 事件同事务——outbox 失败则状态回滚（仍 active）。
		assertThat(accountStatus(accountId)).isEqualTo("active");
	}

	private void failOutboxOn(String eventType) {
		doReturn(Mono.<Void>error(new RuntimeException("outbox injected failure"))).when(outbox)
				.append(argThat((EventEnvelope e) -> e != null && eventType.equals(e.eventType())));
	}

	private long orgCountByOwner(String ownerId) {
		Long c = db.sql("SELECT COUNT(*)::bigint AS c FROM organization WHERE owner_account_id = CAST(:o AS uuid)")
				.bind("o", ownerId).map(row -> row.get("c", Long.class)).one().block();
		return c == null ? 0L : c;
	}

	private long storeCountByOrg(String orgId) {
		Long c = db.sql("SELECT COUNT(*)::bigint AS c FROM store WHERE organization_id = CAST(:o AS uuid)")
				.bind("o", orgId).map(row -> row.get("c", Long.class)).one().block();
		return c == null ? 0L : c;
	}

	/** #48 主体直建一个组织成员子账号（email 形态为现状契约，任务书 #49 S2 改为 loginName 后同步更新）。 */
	@SuppressWarnings("unchecked")
	private String createSubAccount(String orgId, String cookie) {
		String email = "atom-sub-" + UUID.randomUUID() + "@example.com";
		Map<String, Object> body = client().post().uri("/api/organizations/" + orgId + "/accounts")
				.contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
				.bodyValue("{\"email\":\"" + email + "\",\"displayName\":\"A\",\"role\":\"member\"}").exchange()
				.expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
		return (String) ((Map<String, Object>) ((Map<String, Object>) body.get("data")).get("account")).get("id");
	}

	private String accountStatus(String accountId) {
		return db.sql("SELECT status FROM app_users WHERE id = CAST(:id AS uuid)").bind("id", accountId)
				.map(row -> row.get("status", String.class)).one().block();
	}
}
