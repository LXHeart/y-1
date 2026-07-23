package com.grassland.identity.store;

import com.grassland.identity.IdentityItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 端到端验证门店 MANAGER 级独立授权（草场身份域 Slice 2J）。继承 {@link IdentityItSupport}。
 *
 * <p>覆盖：门店经理加/删 staff、经理不能任命 manager（仅 org ADMIN）、staff 不能管理、org ADMIN 隐式管理、末位 manager 守卫。
 * 既有 {@code StoreMembershipControllerIT} 以 org owner（隐式超管）操作，本类专测门店经理独立权限。
 */
class StoreMembershipAuthzIT extends IdentityItSupport {

    @Test
    void storeManagerCanAddAndRemoveStaff() {
        var owner = seedAccount("sj-own@example.com");
        String orgId = createOrg(owner.cookie(), "门店授权主体");
        String storeId = createStore(orgId, owner.cookie(), "门店");
        var manager = seedAccount("sj-mgr@example.com");
        var staff = seedAccount("sj-staff@example.com");

        // owner（org OWNER≥ADMIN）任命门店 manager
        addStoreMember(owner.cookie(), orgId, storeId, manager.accountId(), "manager").expectStatus().isCreated();
        // 门店经理独立加 staff（无需 org ADMIN）
        addStoreMember(manager.cookie(), orgId, storeId, staff.accountId(), "staff").expectStatus().isCreated();
        // 门店经理移除 staff
        client().delete().uri(membershipUri(orgId, storeId, staff.accountId()))
                .header("Cookie", "y1.sid=" + manager.cookie()).exchange().expectStatus().isOk();
    }

    @Test
    void storeManagerCannotAppointManager() {
        var owner = seedAccount("sj-nm-own@example.com");
        String orgId = createOrg(owner.cookie(), "主体");
        String storeId = createStore(orgId, owner.cookie(), "门店");
        var manager = seedAccount("sj-nm-mgr@example.com");
        var other = seedAccount("sj-nm-other@example.com");
        addStoreMember(owner.cookie(), orgId, storeId, manager.accountId(), "manager").expectStatus().isCreated();

        // 门店经理任命另一个 manager → 403（仅 org ADMIN+）
        addStoreMember(manager.cookie(), orgId, storeId, other.accountId(), "manager").expectStatus().isForbidden();
    }

    @Test
    void staffCannotManage() {
        var owner = seedAccount("sj-sf-own@example.com");
        String orgId = createOrg(owner.cookie(), "主体");
        String storeId = createStore(orgId, owner.cookie(), "门店");
        var manager = seedAccount("sj-sf-mgr@example.com");
        var staff = seedAccount("sj-sf-staff@example.com");
        var extra = seedAccount("sj-sf-extra@example.com");
        addStoreMember(owner.cookie(), orgId, storeId, manager.accountId(), "manager").expectStatus().isCreated();
        addStoreMember(manager.cookie(), orgId, storeId, staff.accountId(), "staff").expectStatus().isCreated();

        // staff 加成员 → 403
        addStoreMember(staff.cookie(), orgId, storeId, extra.accountId(), "staff").expectStatus().isForbidden();
        // staff 移除经理 → 403
        client().delete().uri(membershipUri(orgId, storeId, manager.accountId()))
                .header("Cookie", "y1.sid=" + staff.cookie()).exchange().expectStatus().isForbidden();
    }

    @Test
    void orgAdminImplicitlyManagesStore() {
        var owner = seedAccount("sj-adm-own@example.com");
        String orgId = createOrg(owner.cookie(), "主体");
        String storeId = createStore(orgId, owner.cookie(), "门店");
        var admin = seedAccount("sj-adm-admin@example.com");   // 将成为 org admin（非门店成员）
        var staff = seedAccount("sj-adm-staff@example.com");
        addOrgMember(owner.cookie(), orgId, admin.accountId(), "admin");

        // org admin（非门店成员）隐式 MANAGER，可加 staff
        addStoreMember(admin.cookie(), orgId, storeId, staff.accountId(), "staff").expectStatus().isCreated();
    }

    @Test
    void lastManagerCannotBeRemoved() {
        var owner = seedAccount("sj-last-own@example.com");
        String orgId = createOrg(owner.cookie(), "主体");
        String storeId = createStore(orgId, owner.cookie(), "门店");
        var manager = seedAccount("sj-last-mgr@example.com");
        addStoreMember(owner.cookie(), orgId, storeId, manager.accountId(), "manager").expectStatus().isCreated();

        // 即便 org 超管也不能移除唯一 manager（守卫）→ 409
        client().delete().uri(membershipUri(orgId, storeId, manager.accountId()))
                .header("Cookie", "y1.sid=" + owner.cookie()).exchange().expectStatus().isEqualTo(409);
    }

    private String membershipUri(String orgId, String storeId, String accountId) {
        return "/api/organizations/" + orgId + "/stores/" + storeId + "/memberships/" + accountId;
    }

    private void addOrgMember(String ownerCookie, String orgId, String accountId, String role) {
        client().post().uri("/api/organizations/" + orgId + "/memberships")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + ownerCookie)
                .bodyValue("{\"accountId\":\"" + accountId + "\",\"role\":\"" + role + "\"}")
                .exchange().expectStatus().isCreated();
    }

    private WebTestClient.ResponseSpec addStoreMember(String cookie, String orgId, String storeId,
                                                      String accountId, String role) {
        return client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/memberships")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"accountId\":\"" + accountId + "\",\"role\":\"" + role + "\"}")
                .exchange();
    }
}
