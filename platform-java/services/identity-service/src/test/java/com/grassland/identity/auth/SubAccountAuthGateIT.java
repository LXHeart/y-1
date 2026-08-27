package com.grassland.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import com.grassland.identity.security.Argon2PasswordHasher;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 子账号登录门禁与首登强制改密（任务书 #48 S2）。
 *
 * <p>状态矩阵：pending_review/suspended 各自可区分文案；错误密码仍是统一 401 不泄露存在性。
 * 改密形态矩阵：flag 置位免旧密、未置位必须旧密且验错拒绝。
 */
class SubAccountAuthGateIT extends IdentityItSupport {

    private static final String OLD_PASSWORD = "OldPass12345";
    private static final String NEW_PASSWORD = "NewPass12345";

    @Autowired
    private Argon2PasswordHasher argon2Hasher;

    @Test
    void pendingReviewLogin_getsDedicatedMessage() {
        String email = "gate-pending@example.com";
        String accountId = insertUser(email, "pending_review");

        login(email, OLD_PASSWORD).expectStatus().isForbidden()
                .expectBody().jsonPath("$.error").isEqualTo("账号待商家主体审核通过后再登录");
        assertThat(accountId).isNotBlank();
    }

    @Test
    void suspendedLogin_getsDedicatedMessage() {
        insertUser("gate-susp@example.com", "suspended");
        login("gate-susp@example.com", OLD_PASSWORD).expectStatus().isForbidden()
                .expectBody().jsonPath("$.error").isEqualTo("账号已停用，请联系商家管理员");
    }

    @Test
    void wrongPassword_staysGeneric401_evenWhenSuspended() {
        // 存在性与凭据正确性的边界：错密码一律统一 401，不因状态不同而泄露信息
        insertUser("gate-wrongpw@example.com", "suspended");
        login("gate-wrongpw@example.com", "TotallyWrong99").expectStatus().isUnauthorized();
    }

    @Test
    void firstLogin_withFlag_changesPasswordWithoutOld_andMeReflectsCleared() {
        var seeded = seedAccount("gate-first@example.com"); // 有登录态（cookie）
        forceFlag(seeded.accountId());

        client().post().uri("/api/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + seeded.cookie())
                .bodyValue("{\"newPassword\":\"" + NEW_PASSWORD + "\"}")
                .exchange().expectStatus().isOk();

        Boolean cleared = db.sql(
                        "SELECT must_change_password FROM account_flag WHERE account_id = CAST(:id AS uuid)")
                .bind("id", seeded.accountId())
                .map(r -> r.get("must_change_password", Boolean.class)).one().block();
        assertThat(cleared).isFalse();

        // 新密码可登录（hash 已换）
        login("gate-first@example.com", NEW_PASSWORD).expectStatus().isOk()
                .expectBody().jsonPath("$.data.user.mustChangePassword").isEqualTo(false);
    }

    @Test
    void regularUser_mustProvideCorrectCurrentPassword() {
        var seeded = seedAccount("gate-reg@example.com"); // 无旗标行 = 常规改密

        client().post().uri("/api/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + seeded.cookie())
                .bodyValue("{\"newPassword\":\"" + NEW_PASSWORD + "\"}")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("请输入当前密码");

        client().post().uri("/api/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + seeded.cookie())
                .bodyValue("{\"currentPassword\":\"nope\",\"newPassword\":\"" + NEW_PASSWORD + "\"}")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("当前密码不正确");

        client().post().uri("/api/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + seeded.cookie())
                .bodyValue("{\"currentPassword\":\"x\",\"newPassword\":\"" + NEW_PASSWORD + "\"}")
                .exchange().expectStatus().isBadRequest();
    }

    // ---------- helpers ----------

    /** 用真实 Argon2 哈希插一个指定状态的账号，密码固定 {@link #OLD_PASSWORD}。返回 accountId。 */
    private String insertUser(String email, String status) {
        String id = UUID.randomUUID().toString();
        String hash = argon2Hasher.hash(OLD_PASSWORD);
        db.sql("""
                        INSERT INTO app_users(id, email, password_hash, display_name, role, status)
                        VALUES (CAST(:id AS uuid), :email, :hash, '门禁测试', 'user', :status)
                        """)
                .bind("id", id).bind("email", email).bind("hash", hash).bind("status", status)
                .then().block();
        return id;
    }

    private void forceFlag(String accountId) {
        db.sql("""
                        INSERT INTO account_flag(account_id, must_change_password)
                        VALUES (CAST(:id AS uuid), TRUE)
                        ON CONFLICT (account_id) DO UPDATE SET must_change_password = TRUE
                        """)
                .bind("id", accountId).then().block();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec login(String email,
            String password) {
        return client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .exchange();
    }
}
