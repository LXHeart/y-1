package com.grassland.identity.store;

import com.grassland.identity.IdentityItSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 门店停用/恢复/删除生命周期（2026-08-27：门店此前只能新增）。
 *
 * <p>停用=可逆（status active↔suspended，对外隐藏、管理不受影响）；删除=软删不可逆
 * （deleted_at 留痕，列表/单查/授权全消失）。删除三守卫：最后一家店/店内有成员/店内有任务。
 */
class StoreLifecycleIT extends IdentityItSupport {

    @org.junit.jupiter.api.BeforeEach
    void minimalTaskTable() {
        // 守卫③只读 COUNT marketplace.task（五服务共库逻辑隔离）；identity 测试库没有该表，
        // 建最小结构（幂等，单例容器跨测试共享）
        db.sql("CREATE TABLE IF NOT EXISTS task (id uuid PRIMARY KEY, store_id uuid)").then().block();
    }

    @Test
    void suspendAndRestore_roundTripWithEvent() {
        var owner = seedAccount("sl-susp@example.com");
        String cookie = owner.cookie();
        String orgId = createOrg(cookie, "停用主体");
        createStore(orgId, cookie, "A店");
        String storeId = createStore(orgId, cookie, "B店");

        client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/suspend")
                .header("Cookie", "y1.sid=" + cookie).exchange().expectStatus().isOk();

        String status = storeStatus(storeId);
        org.assertj.core.api.Assertions.assertThat(status).isEqualTo("suspended");
        Long events = countEvents(storeId, "StoreStatusChanged");
        org.assertj.core.api.Assertions.assertThat(events).isEqualTo(1);

        // 已停用再停用 → 409；恢复 → active；再恢复 → 409
        client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/suspend")
                .header("Cookie", "y1.sid=" + cookie).exchange().expectStatus().isEqualTo(409);
        client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/restore")
                .header("Cookie", "y1.sid=" + cookie).exchange().expectStatus().isOk();
        org.assertj.core.api.Assertions.assertThat(storeStatus(storeId)).isEqualTo("active");
        client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/restore")
                .header("Cookie", "y1.sid=" + cookie).exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void delete_guards_lastStoreMembersTasks_thenSoftDeletes() {
        var owner = seedAccount("sl-del@example.com");
        String cookie = owner.cookie();
        String orgId = createOrg(cookie, "删除主体");
        createStore(orgId, cookie, "主店");
        String storeId = createStore(orgId, cookie, "待删店");

        // 守卫②：店内有成员 → 409
        client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/accounts")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"role\":\"staff\",\"loginName\":\"slguard" + java.util.UUID.randomUUID().toString().substring(0, 6) + "\",\"displayName\":\"占位员工\"}")
                .exchange().expectStatus().isCreated();
        client().delete().uri("/api/organizations/" + orgId + "/stores/" + storeId)
                .header("Cookie", "y1.sid=" + cookie).exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").isEqualTo("门店下仍有成员，请先删除该店全部成员");
        // 清人（#49 删除动作，直接置成员账号 deleted）后守卫放行下一道
        db.sql("DELETE FROM store_membership WHERE store_id = CAST(:store AS uuid)").bind("store", storeId)
                .then().block();

        // 守卫③：店内有任务 → 409
        db.sql("INSERT INTO task(id, store_id) VALUES (gen_random_uuid(), CAST(:store AS uuid))")
                .bind("store", storeId).then().block();
        client().delete().uri("/api/organizations/" + orgId + "/stores/" + storeId)
                .header("Cookie", "y1.sid=" + cookie).exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").isEqualTo("门店下存在任务记录，不可删除；可停用");
        db.sql("DELETE FROM task WHERE store_id = CAST(:store AS uuid)").bind("store", storeId).then().block();

        // 通过：软删落痕、列表消失、单查 404、对已删店授权 404
        client().delete().uri("/api/organizations/" + orgId + "/stores/" + storeId)
                .header("Cookie", "y1.sid=" + cookie).exchange().expectStatus().isOk();
        String deletedAt = db.sql("SELECT deleted_at::text FROM store WHERE id = CAST(:id AS uuid)")
                .bind("id", storeId).map(r -> r.get(0, String.class)).one().block();
        org.assertj.core.api.Assertions.assertThat(deletedAt).isNotBlank();
        client().get().uri("/api/organizations/" + orgId + "/stores")
                .header("Cookie", "y1.sid=" + cookie).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.data[?(@.id=='" + storeId + "')]").doesNotExist();
        client().get().uri("/api/organizations/" + orgId + "/stores/" + storeId)
                .header("Cookie", "y1.sid=" + cookie).exchange().expectStatus().isNotFound();
        // 已删店不可再停用（查询已过滤 → 404）
        client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/suspend")
                .header("Cookie", "y1.sid=" + cookie).exchange().expectStatus().isNotFound();

        // 守卫①：单店组织的唯一门店（注册默认店）不可删——不经营可停用
        // （一账号一主体规则：换新账号建单店组织）
        var soloOwner = seedAccount("sl-solo-" + java.util.UUID.randomUUID().toString().substring(0, 6)
                + "@example.com");
        String soloOrg = createOrg(soloOwner.cookie(), "单店主体");
        String soloStore = firstStoreOf(soloOrg);
        client().delete().uri("/api/organizations/" + soloOrg + "/stores/" + soloStore)
                .header("Cookie", "y1.sid=" + soloOwner.cookie()).exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").isEqualTo("主体必须保留至少一家门店；不经营可停用");
    }

    // ---------- helpers ----------

    private String storeStatus(String storeId) {
        return db.sql("SELECT status FROM store WHERE id = CAST(:id AS uuid)")
                .bind("id", storeId).map(r -> r.get("status", String.class)).one().block();
    }

    private Long countEvents(String storeId, String eventType) {
        return db.sql("SELECT COUNT(*)::int AS c FROM outbox"
                        + " WHERE event_type = :type AND payload->>'storeId' = :store")
                .bind("type", eventType).bind("store", storeId)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
    }

    private String firstStoreOf(String orgId) {
        return db.sql("SELECT id::text FROM store WHERE organization_id = CAST(:org AS uuid)"
                        + " AND deleted_at IS NULL ORDER BY created_at LIMIT 1")
                .bind("org", orgId).map(r -> r.get(0, String.class)).one().block();
    }
}
