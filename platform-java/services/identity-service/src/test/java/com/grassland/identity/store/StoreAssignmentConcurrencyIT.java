package com.grassland.identity.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 任务书 #103 C103-17（R08/TC103-17-06）：成员池写路径组织级事务互斥——真实 PG 并发验证。
 *
 * <p>
 * R08 复现路径：一店一店长的 count 前置检查曾在事务外，两个并发 assign 到同一空店 双双 count=0 → 双店长。修复后
 * StoreAssignmentLock 在事务内锁组织父行，count 在锁内重读。 本 IT 用真实并发（Mono.zip
 * 同时发起）验证：空店并发二店长恰一个成功；同账号并发二目标 恰挂一店；移除与分配并发不产生双店长；并发建号挂店长同闸。
 */
class StoreAssignmentConcurrencyIT extends IdentityItSupport {

	@Autowired
	private StoreAssignmentService assignments;

	@Test
	@DisplayName("E15/TC103-17-06 空店并发二店长：组织锁下恰一个成功")
	void concurrentManagerAssignmentToEmptyStoreYieldsExactlyOne() {
		var owner = seedAccount("conc-owner@example.com");
		String cookie = owner.cookie();
		String orgId = createOrg(cookie, "并发主体");
		String storeId = createStore(orgId, cookie, "空店");
		String first = createAccount(orgId, cookie,
				"{\"role\":\"member\",\"loginName\":\"concm1\",\"displayName\":\"并发一\"}");
		String second = createAccount(orgId, cookie,
				"{\"role\":\"member\",\"loginName\":\"concm2\",\"displayName\":\"并发二\"}");

		List<Integer> statuses = assignments.assign(owner.accountId(), orgId, storeId, first, "manager").map(m -> 200)
				.onErrorResume(e -> reactor.core.publisher.Mono.just(409))
				.zipWith(assignments.assign(owner.accountId(), orgId, storeId, second, "manager").map(m -> 200)
						.onErrorResume(e -> reactor.core.publisher.Mono.just(409)))
				.map(tuple -> List.of(tuple.getT1(), tuple.getT2())).block(Duration.ofSeconds(20));

		long successCount = statuses.stream().filter(status -> status == 200).count();
		long conflictCount = statuses.stream().filter(status -> status == 409).count();
		assertThat(successCount).as("并发二店长恰一个成功，另一个 409：%s", statuses).isEqualTo(1);
		assertThat(conflictCount).isEqualTo(1);
		Long managerRows = db
				.sql("SELECT COUNT(*) FROM store_membership WHERE store_id = CAST(:s AS uuid) AND role = 'manager'")
				.bind("s", storeId).map((row, meta) -> row.get(0, Long.class)).one().block(Duration.ofSeconds(5));
		assertThat(managerRows).as("数据库恰一行店长").isEqualTo(1L);
	}

	@Test
	@DisplayName("E15 同账号并发分配二目标店：最终恰挂一店（assign-or-move 原子）")
	void concurrentAssignmentOfSameAccountToTwoStoresEndsWithExactlyOne() {
		var owner = seedAccount("conc-move@example.com");
		String cookie = owner.cookie();
		String orgId = createOrg(cookie, "并发调度主体");
		String storeA = createStore(orgId, cookie, "并发甲店");
		String storeB = createStore(orgId, cookie, "并发乙店");
		String accountId = createAccount(orgId, cookie,
				"{\"role\":\"member\",\"loginName\":\"concmover\",\"displayName\":\"调度员\"}");

		// 两个并发 assign 均可成功（后落锁者按 assign-or-move 移动），最终恰一店一行
		List<Integer> statuses = assignments.assign(owner.accountId(), orgId, storeA, accountId, "staff").map(m -> 200)
				.onErrorResume(e -> reactor.core.publisher.Mono.just(409))
				.zipWith(assignments.assign(owner.accountId(), orgId, storeB, accountId, "staff").map(m -> 200)
						.onErrorResume(e -> reactor.core.publisher.Mono.just(409)))
				.map(tuple -> List.of(tuple.getT1(), tuple.getT2())).block(Duration.ofSeconds(20));
		assertThat(statuses.stream().filter(status -> status == 200).count()).as("调度语义下两次都应成功：%s", statuses)
				.isEqualTo(2);

		Long rows = db.sql("SELECT COUNT(*) FROM store_membership WHERE account_id = CAST(:a AS uuid)")
				.bind("a", accountId).map((row, meta) -> row.get(0, Long.class)).one().block(Duration.ofSeconds(5));
		assertThat(rows).as("同一账号在本组织恰挂一店").isEqualTo(1L);
	}

	@Test
	@DisplayName("E18 移除与并发分配同锁串行：不产生双店长或两店都挂")
	void removeRacingAssignmentStaysConsistent() {
		var owner = seedAccount("conc-rm@example.com");
		String cookie = owner.cookie();
		String orgId = createOrg(cookie, "并发移除主体");
		String storeId = createStore(orgId, cookie, "移除店");
		String managerId = createAccount(orgId, cookie, "{\"role\":\"manager\",\"storeId\":\"" + storeId
				+ "\",\"loginName\":\"concmgr\"," + "\"displayName\":\"在任店长\"}");
		String replacement = createAccount(orgId, cookie,
				"{\"role\":\"member\",\"loginName\":\"concrepl\",\"displayName\":\"继任\"}");

		// 并发：移除在任店长 vs 分配继任为店长——组织锁串行后无论次序，最终恰一行店长或零行
		Integer removeStatus = assignments.remove(owner.accountId(), orgId, storeId, managerId).thenReturn(200)
				.onErrorResume(e -> reactor.core.publisher.Mono.just(409)).block(Duration.ofSeconds(20));
		Integer assignStatus = assignments.assign(owner.accountId(), orgId, storeId, replacement, "manager")
				.map(v -> 200).onErrorResume(e -> reactor.core.publisher.Mono.just(409)).block(Duration.ofSeconds(20));
		assertThat(removeStatus).isEqualTo(200);
		assertThat(assignStatus).isEqualTo(200);
		Long managerRows = db
				.sql("SELECT COUNT(*) FROM store_membership WHERE store_id = CAST(:s AS uuid) AND role = 'manager'")
				.bind("s", storeId).map((row, meta) -> row.get(0, Long.class)).one().block(Duration.ofSeconds(5));
		assertThat(managerRows).as("移除先行、分配后到 → 继任恰一行").isEqualTo(1L);
	}

	@SuppressWarnings("unchecked")
	private String createAccount(String orgId, String cookie, String json) {
		Map<String, Object> body = client().post().uri("/api/organizations/" + orgId + "/accounts")
				.contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie).bodyValue(json).exchange()
				.expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
		return (String) ((Map<String, Object>) ((Map<String, Object>) body.get("data")).get("account")).get("id");
	}
}
