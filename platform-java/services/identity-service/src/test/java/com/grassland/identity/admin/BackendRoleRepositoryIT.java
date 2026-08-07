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
        db.sql("UPDATE app_users SET role='admin' WHERE id=CAST(:id AS uuid)")
                .bind("id", seeded.accountId()).then().block();

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
                    roles.revoke(seeded.accountId(), BackendRole.CUSTOMER_SERVICE)
                            .subscribeOn(Schedulers.parallel()))
                    .block();

            assertThat(roles.findByAccountId(seeded.accountId()).block())
                    .containsExactly(BackendRole.PLATFORM_ADMIN);
            assertThat(legacyRole(seeded.accountId())).isEqualTo("admin");
        }
    }

    private String legacyRole(String accountId) {
        return db.sql("SELECT role FROM app_users WHERE id=CAST(:id AS uuid)")
                .bind("id", accountId)
                .map(row -> row.get("role", String.class))
                .one().block();
    }
}
