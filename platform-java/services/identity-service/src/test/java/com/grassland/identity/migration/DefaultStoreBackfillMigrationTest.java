package com.grassland.identity.migration;

import com.grassland.identity.IdentityItSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * V46 回填迁移语义（任务书 #50 D3/S2）：零门店主体补建默认店（名=主体名）；
 * 有店主体不受影响；幂等重放。
 */
class DefaultStoreBackfillMigrationTest extends IdentityItSupport {

    @Test
    void zeroStoreOrgGetsDefaultStore_othersUntouched_idempotent() {
        // 两个主体：一个零店、一个已有一家店（直插 organization 必须带 V43 前缀列）
        var owner = seedAccount("v46-a@example.com");
        String emptyOrg = UUID.randomUUID().toString();
        db.sql("INSERT INTO organization(id, owner_account_id, name, status, account_prefix)"
                + " VALUES (CAST(:org AS uuid), CAST(:owner AS uuid), '零店主体', 'active', 'v46aaa11')")
                .bind("org", emptyOrg).bind("owner", owner.accountId()).then().block();
        String stockedOrg = UUID.randomUUID().toString();
        db.sql("INSERT INTO organization(id, owner_account_id, name, status, account_prefix)"
                + " VALUES (CAST(:org AS uuid), CAST(:owner AS uuid), '一店主体', 'active', 'v46bbb22')")
                .bind("org", stockedOrg).bind("owner", owner.accountId()).then().block();
        db.sql("INSERT INTO store(id, organization_id, name, status)"
                + " VALUES (gen_random_uuid(), CAST(:org AS uuid), '已有店', 'active')")
                .bind("org", stockedOrg).then().block();

        runMigration("V46__backfill_default_store.sql");

        // 零店主体 → 补一家默认店（名=主体名）
        Long emptyOrgStores = db.sql("SELECT COUNT(*)::int AS c FROM store"
                        + " WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", emptyOrg).map(r -> r.get("c", Integer.class)).one().block().longValue();
        org.assertj.core.api.Assertions.assertThat(emptyOrgStores).isEqualTo(1);
        String backfilled = db.sql("SELECT name FROM store WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", emptyOrg).map(r -> r.get("name", String.class)).one().block();
        org.assertj.core.api.Assertions.assertThat(backfilled).isEqualTo("零店主体");

        // 有店主体不受影响（仍 1 家，名字不变）
        Long stockedCount = db.sql("SELECT COUNT(*)::int AS c FROM store"
                        + " WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", stockedOrg).map(r -> r.get("c", Integer.class)).one().block().longValue();
        org.assertj.core.api.Assertions.assertThat(stockedCount).isEqualTo(1);

        // 幂等：重放后零店主体仍是 1 家（不重复补建）
        runMigration("V46__backfill_default_store.sql");
        Long afterReplay = db.sql("SELECT COUNT(*)::int AS c FROM store"
                        + " WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", emptyOrg).map(r -> r.get("c", Integer.class)).one().block().longValue();
        org.assertj.core.api.Assertions.assertThat(afterReplay).isEqualTo(1);
    }

    /** 在共享库上直接重放 V46 的 SQL（语义重放；Flyway 历史表已记录正式执行）。 */
    private void runMigration(String file) {
        String sql;
        try {
            sql = new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                    "src/main/resources/db/migration/" + file)), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        db.sql(sql).then().block();
    }
}
