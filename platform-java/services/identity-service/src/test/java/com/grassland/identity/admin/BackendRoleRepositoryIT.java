package com.grassland.identity.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import com.grassland.identity.assertion.BackendRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class BackendRoleRepositoryIT extends IdentityItSupport {

	@Autowired
	BackendRoleRepository roles;

	@Test
	void grantAndRevokeAtomicallyMaintainTheLegacyRoleProjection() {
		Seeded seeded = seedAccount("role-projection@grassland.local");

		roles.grant(seeded.accountId(), BackendRole.CUSTOMER_SERVICE, seeded.accountId()).block();
		assertThat(legacyRole(seeded.accountId())).isEqualTo("customer_service");

		roles.grant(seeded.accountId(), BackendRole.PLATFORM_ADMIN, seeded.accountId()).block();
		assertThat(legacyRole(seeded.accountId())).isEqualTo("admin");

		roles.revoke(seeded.accountId(), BackendRole.PLATFORM_ADMIN).block();
		assertThat(legacyRole(seeded.accountId())).isEqualTo("customer_service");

		roles.revoke(seeded.accountId(), BackendRole.CUSTOMER_SERVICE).block();
		assertThat(legacyRole(seeded.accountId())).isEqualTo("user");
	}

	@Test
	void revokingAMissingMigratedRoleClearsAStaleLegacyProjection() {
		Seeded seeded = seedAccount("stale-role-projection@grassland.local");
		db.sql("UPDATE app_users SET role='admin' WHERE id=CAST(:id AS uuid)").bind("id", seeded.accountId()).then()
				.block();

		roles.revoke(seeded.accountId(), BackendRole.PLATFORM_ADMIN).block();

		assertThat(legacyRole(seeded.accountId())).isEqualTo("user");
	}

	@Test
	void concurrentGrantAndRevokeKeepTheLegacyProjectionConsistent() {
		for (int attempt = 0; attempt < 12; attempt++) {
			Seeded seeded = seedAccount("concurrent-role-" + attempt + "@grassland.local");
			roles.grant(seeded.accountId(), BackendRole.CUSTOMER_SERVICE, seeded.accountId()).block();

			Mono.when(
					roles.grant(seeded.accountId(), BackendRole.PLATFORM_ADMIN, seeded.accountId())
							.subscribeOn(Schedulers.parallel()),
					roles.revoke(seeded.accountId(), BackendRole.CUSTOMER_SERVICE).subscribeOn(Schedulers.parallel()))
					.block();

			assertThat(roles.findByAccountId(seeded.accountId()).block()).containsExactly(BackendRole.PLATFORM_ADMIN);
			assertThat(legacyRole(seeded.accountId())).isEqualTo("admin");
		}
	}

	@Test
	void findByAccountIdsCoversAllInputsWithoutUnroledAccounts() {
		// 任务书 #94 D94-10：批量 IN 查询是列表富化的唯一路径
		Seeded cs = seedAccount("batch-cs@grassland.local");
		Seeded dual = seedAccount("batch-dual@grassland.local");
		Seeded none = seedAccount("batch-none@grassland.local");
		roles.grant(cs.accountId(), BackendRole.CUSTOMER_SERVICE, cs.accountId()).block();
		roles.grant(dual.accountId(), BackendRole.CUSTOMER_SERVICE, dual.accountId()).block();
		roles.grant(dual.accountId(), BackendRole.RISK, dual.accountId()).block();

		var rows = roles.findByAccountIds(java.util.List.of(cs.accountId(), dual.accountId(), none.accountId()))
				.collectList().block();

		assertThat(rows).extracting(BackendRoleRepository.AccountRole::accountId)
				.containsExactlyInAnyOrder(cs.accountId(), dual.accountId(), dual.accountId());
		assertThat(rows).extracting(BackendRoleRepository.AccountRole::role).containsExactlyInAnyOrder(
				BackendRole.CUSTOMER_SERVICE, BackendRole.CUSTOMER_SERVICE, BackendRole.RISK);
		// 与单账号查询同口径：无角色账号不在结果里
		assertThat(rows).noneMatch(row -> row.accountId().equals(none.accountId()));
		// 空入参 → empty（不发 SQL）
		assertThat(roles.findByAccountIds(java.util.List.of()).collectList().block()).isEmpty();
	}

	private String legacyRole(String accountId) {
		return db.sql("SELECT role FROM app_users WHERE id=CAST(:id AS uuid)").bind("id", accountId)
				.map(row -> row.get("role", String.class)).one().block();
	}
}
