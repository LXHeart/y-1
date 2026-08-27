package com.grassland.identity.migration;

import com.grassland.identity.IdentityItSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * V45 存量清理迁移语义（任务书 #49 D3/S6）：Flyway 全量重放（含 V45）后断言边界。
 *
 * <p>owner 关系保留（role='owner' 或账号即 owner_account_id）；纯门店经理（无组织层挂靠）
 * 的门店关系不受影响；普通成员（挂靠产生）两侧关系清零并各落一条站内信；pending 邀请作废。
 */
class SingleMemberModelCleanupMigrationTest extends IdentityItSupport {

    @Test
    void ownerKept_storeOnlyManagerKept_memberRevokedWithNotice() {
        // 造一个「owner + admin 挂靠 + 纯成员挂靠 + 纯门店经理（无组织层行）」的存量形态，
        // 再造一个 pending 邀请——全部走 SQL 直插（复刻迁移前的存量数据形态）
        var owner = seedAccount("v45-owner@example.com");
        var admin = seedAccount("v45-admin@example.com");
        var member = seedAccount("v45-member@example.com");
        var storeOnly = seedAccount("v45-storeonly@example.com");
        String orgId = UUID.randomUUID().toString();
        db.sql("INSERT INTO organization(id, owner_account_id, name, status, account_prefix)"
                + " VALUES (CAST(:org AS uuid), CAST(:owner AS uuid), '存量主体', 'active', 'v45keepme')")
                .bind("org", orgId).bind("owner", owner.accountId()).then().block();
        // owner 双形态：owner_account_id 命中 + role='owner' 行（best-effort 种的历史行）
        db.sql("INSERT INTO organization_membership(id, organization_id, account_id, role)"
                + " VALUES (gen_random_uuid(), CAST(:org AS uuid), CAST(:owner AS uuid), 'owner')")
                .bind("org", orgId).bind("owner", owner.accountId()).then().block();
        db.sql("INSERT INTO organization_membership(id, organization_id, account_id, role)"
                + " VALUES (gen_random_uuid(), CAST(:org AS uuid), CAST(:admin AS uuid), 'admin')")
                .bind("org", orgId).bind("admin", admin.accountId()).then().block();
        db.sql("INSERT INTO organization_membership(id, organization_id, account_id, role)"
                + " VALUES (gen_random_uuid(), CAST(:org AS uuid), CAST(:member AS uuid), 'member')")
                .bind("org", orgId).bind("member", member.accountId()).then().block();
        String storeId = db.sql("INSERT INTO store(id, organization_id, name, status)"
                + " VALUES (gen_random_uuid(), CAST(:org AS uuid), '存量店', 'active') RETURNING id::text")
                .bind("org", orgId).map(r -> r.get(0, String.class)).one().block();
        db.sql("INSERT INTO store_membership(id, store_id, account_id, role)"
                + " VALUES (gen_random_uuid(), CAST(:store AS uuid), CAST(:member AS uuid), 'staff')")
                .bind("store", storeId).bind("member", member.accountId()).then().block();
        // 纯门店经理：只有门店关系，无组织层行（KYB 自建形态）——不得被清理
        db.sql("INSERT INTO store_membership(id, store_id, account_id, role)"
                + " VALUES (gen_random_uuid(), CAST(:store AS uuid), CAST(:sm AS uuid), 'manager')")
                .bind("store", storeId).bind("sm", storeOnly.accountId()).then().block();
        db.sql("INSERT INTO organization_invitation(id, organization_id, email, role, status,"
                + " invited_by_account_id, expires_at)"
                + " VALUES (gen_random_uuid(), CAST(:org AS uuid), 'v45-pending@example.com', 'member', 'pending',"
                + " CAST(:owner AS uuid), now() + interval '7 days')")
                .bind("org", orgId).bind("owner", owner.accountId()).then().block();

        // 触发 V45 重放（TaskLifecycleMigrationTest 同款：重放 = 在同一库上再执行一次迁移语义）
        runMigration("V45__single_member_model_cleanup.sql");

        // owner 行保留
        Long ownerRows = countOrgRows(orgId, owner.accountId());
        org.assertj.core.api.Assertions.assertThat(ownerRows).isEqualTo(1);
        // 挂靠 admin/member 清零
        org.assertj.core.api.Assertions.assertThat(countOrgRows(orgId, admin.accountId())).isZero();
        org.assertj.core.api.Assertions.assertThat(countOrgRows(orgId, member.accountId())).isZero();
        // member 的门店行随组织层清零；纯门店经理的门店行保留
        org.assertj.core.api.Assertions.assertThat(countStoreRows(storeId, member.accountId())).isZero();
        org.assertj.core.api.Assertions.assertThat(countStoreRows(storeId, storeOnly.accountId())).isEqualTo(1);
        // 被清成员各一条 SYSTEM 站内信；owner（保留方）不产生
        Long notices = db.sql("SELECT COUNT(*)::int AS c FROM notification"
                        + " WHERE event_type = 'LegacyMembershipRevoked' AND account_id = CAST(:acct AS uuid)")
                .bind("acct", member.accountId())
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        org.assertj.core.api.Assertions.assertThat(notices).isEqualTo(1);
        // pending 邀请作废（留痕）
        String invitationStatus = db.sql("SELECT status FROM organization_invitation"
                        + " WHERE organization_id = CAST(:org AS uuid) AND email = 'v45-pending@example.com'")
                .bind("org", orgId).map(r -> r.get("status", String.class)).one().block();
        org.assertj.core.api.Assertions.assertThat(invitationStatus).isEqualTo("cancelled");

        // 幂等：重放第二次零副作用（通知不重复）
        runMigration("V45__single_member_model_cleanup.sql");
        Long noticesAgain = db.sql("SELECT COUNT(*)::int AS c FROM notification"
                        + " WHERE event_type = 'LegacyMembershipRevoked' AND account_id = CAST(:acct AS uuid)")
                .bind("acct", member.accountId())
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        org.assertj.core.api.Assertions.assertThat(noticesAgain).isEqualTo(1);
    }

    private Long countOrgRows(String orgId, String accountId) {
        return db.sql("SELECT COUNT(*)::int AS c FROM organization_membership"
                        + " WHERE organization_id = CAST(:org AS uuid) AND account_id = CAST(:acct AS uuid)")
                .bind("org", orgId).bind("acct", accountId)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
    }

    private Long countStoreRows(String storeId, String accountId) {
        return db.sql("SELECT COUNT(*)::int AS c FROM store_membership"
                        + " WHERE store_id = CAST(:store AS uuid) AND account_id = CAST(:acct AS uuid)")
                .bind("store", storeId).bind("acct", accountId)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
    }

    /** 在共享库上直接重放 V45 的 SQL（Flyway 历史表已记录正式执行，这里是语义重放）。 */
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
