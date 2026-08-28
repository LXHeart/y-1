package com.grassland.identity.migration;

import com.grassland.identity.IdentityItSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * V49 回填迁移语义（问题二②）：商家身份档案 organization_id=NULL 且账号是某主体 owner →
 * 按 owner 关系回填（「登录先开通 → 后建主体」历史序列的存量）；已绑定/推荐官档案不动；幂等重放。
 */
class MerchantIdentityOrganizationBackfillMigrationTest extends IdentityItSupport {

    @Test
    void ownerBackfilled_alreadyBoundUntouched_idempotent() {
        // 形态 A：owner 档案 NULL（待回填）；形态 B：owner 档案已绑别家 org（不动）；
        // 形态 C：推荐官档案（identity_type 不符，不动）；形态 D：member 档案 NULL（非 owner，不动）。
        var backfillOwner = seedAccount("v49-backfill@example.com");
        var boundOwner = seedAccount("v49-bound@example.com");
        var member = seedAccount("v49-member@example.com");
        String backfillOrg = UUID.randomUUID().toString();
        String boundOrg = UUID.randomUUID().toString();
        String otherOrg = UUID.randomUUID().toString();
        db.sql("INSERT INTO organization(id, owner_account_id, name, status, account_prefix)"
                + " VALUES (CAST(:org AS uuid), CAST(:owner AS uuid), '回填主体', 'active', 'v49aaa11')")
                .bind("org", backfillOrg).bind("owner", backfillOwner.accountId()).then().block();
        db.sql("INSERT INTO organization(id, owner_account_id, name, status, account_prefix)"
                + " VALUES (CAST(:org AS uuid), CAST(:owner AS uuid), '已绑主体', 'active', 'v49bbb22')")
                .bind("org", boundOrg).bind("owner", boundOwner.accountId()).then().block();
        db.sql("INSERT INTO organization(id, owner_account_id, name, status, account_prefix)"
                + " VALUES (CAST(:org AS uuid), CAST(:owner AS uuid), '成员主体', 'active', 'v49ccc33')")
                .bind("org", otherOrg).bind("owner", UUID.randomUUID().toString()).then().block();
        db.sql("INSERT INTO organization_membership(id, organization_id, account_id, role)"
                + " VALUES (gen_random_uuid(), CAST(:org AS uuid), CAST(:acct AS uuid), 'member')")
                .bind("org", otherOrg).bind("acct", member.accountId()).then().block();
        db.sql("INSERT INTO identity_profile(id, account_id, identity_type, organization_id, status)"
                + " VALUES (gen_random_uuid(), CAST(:acct AS uuid), 'merchant', NULL, 'active')")
                .bind("acct", backfillOwner.accountId()).then().block();
        db.sql("INSERT INTO identity_profile(id, account_id, identity_type, organization_id, status)"
                + " VALUES (gen_random_uuid(), CAST(:acct AS uuid), 'merchant', CAST(:org AS uuid), 'active')")
                .bind("acct", boundOwner.accountId()).bind("org", otherOrg).then().block();
        db.sql("INSERT INTO identity_profile(id, account_id, identity_type, organization_id, status)"
                + " VALUES (gen_random_uuid(), CAST(:acct AS uuid), 'recommender', NULL, 'active')")
                .bind("acct", member.accountId()).then().block();
        db.sql("INSERT INTO identity_profile(id, account_id, identity_type, organization_id, status)"
                + " VALUES (gen_random_uuid(), CAST(:acct AS uuid), 'merchant', NULL, 'active')")
                .bind("acct", member.accountId()).then().block();

        runMigration("V49__backfill_merchant_identity_organization.sql");

        // A：回填到自有主体
        org.assertj.core.api.Assertions.assertThat(organizationOf(backfillOwner.accountId(), "merchant"))
                .isEqualTo(backfillOrg);
        // B：已绑定不覆盖
        org.assertj.core.api.Assertions.assertThat(organizationOf(boundOwner.accountId(), "merchant"))
                .isEqualTo(otherOrg);
        // C：推荐官档案不动
        org.assertj.core.api.Assertions.assertThat(organizationOf(member.accountId(), "recommender")).isEmpty();
        // D：非 owner 的 NULL 档案不动（其主体归属应由运行时绑定，不由 owner 回填）
        org.assertj.core.api.Assertions.assertThat(organizationOf(member.accountId(), "merchant")).isEmpty();

        // 幂等：重放零副作用
        runMigration("V49__backfill_merchant_identity_organization.sql");
        org.assertj.core.api.Assertions.assertThat(organizationOf(backfillOwner.accountId(), "merchant"))
                .isEqualTo(backfillOrg);
    }

    /** organization_id::text（NULL → 空串；reactor 禁发 null，用 coalesce 适配 .one()）。 */
    private String organizationOf(String accountId, String identityType) {
        return db.sql("SELECT coalesce(organization_id::text, '') AS org FROM identity_profile"
                        + " WHERE account_id = CAST(:acct AS uuid) AND identity_type = :type")
                .bind("acct", accountId).bind("type", identityType)
                .map(r -> r.get("org", String.class)).one().block();
    }

    /** 在共享库上直接重放 V49 的 SQL（语义重放；Flyway 历史表已记录正式执行）。 */
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
