package com.grassland.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import com.grassland.identity.security.Argon2PasswordHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 登录标识双轨（任务书 #49 S3/D7）：账号名（account_username 旁表）与邮箱（存量路径）双查。
 *
 * <p>回归底线：存量邮箱登录路径逐字节不变；子账号可用完整账号名登录，响应带
 * {@code username} 与 {@code hasEmail=false}（占位邮箱不暴露为真邮箱）。
 */
class LoginIdentifierIT extends IdentityItSupport {

    private static final String PASSWORD = "OldPass12345"; // secret-scan: allow - test fixture

    @Autowired
    private Argon2PasswordHasher argon2Hasher;

    @Test
    void legacyEmailLogin_unchanged_andHasNoUsername() {
        String email = "ident-legacy@example.com";
        insertUser(email, "user", null);

        login(email, PASSWORD).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.user.email").isEqualTo(email)
                .jsonPath("$.data.user.hasEmail").isEqualTo(true)
                .jsonPath("$.data.user.username").doesNotExist();
    }

    @Test
    void subAccountLogsInByUsernameWithPlaceholderEmailHidden() {
        String username = "identprefix-zhang3";
        String accountId = insertUser(username + "@sub.grassland.invalid", "user", username);

        login(username, PASSWORD).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.user.id").isEqualTo(accountId)
                .jsonPath("$.data.user.username").isEqualTo(username)
                .jsonPath("$.data.user.hasEmail").isEqualTo(false);
    }

    @Test
    void subAccountAlsoLogsInByPlaceholderEmail() {
        String username = "identprefix-li4";
        insertUser(username + "@sub.grassland.invalid", "user", username);

        login(username + "@sub.grassland.invalid", PASSWORD).expectStatus().isOk();
    }

    @Test
    void usernameLookup_isCaseInsensitive() {
        String username = "identprefix-wang5";
        insertUser(username + "@sub.grassland.invalid", "user", username);

        login("IDENTPREFIX-WANG5", PASSWORD).expectStatus().isOk();
    }

    @Test
    void unknownIdentifier_staysGeneric401() {
        login("nobody-ident@example.com", PASSWORD).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error").isEqualTo("账号或密码错误");
        login("ghostprefix-nobody", PASSWORD).expectStatus().isUnauthorized();
    }

    // ---------- helpers ----------

    /** 直插 app_users（+可选登录名旁表行），密码统一 Argon2 哈希，与真实登录链路一致。 */
    private String insertUser(String email, String role, String username) {
        String accountId = java.util.UUID.randomUUID().toString();
        String hash = argon2Hasher.hash(PASSWORD);
        db.sql("INSERT INTO app_users(id, email, password_hash, display_name, role, status)"
                + " VALUES (CAST(:id AS uuid), :email, :hash, :name, :role, 'active')")
                .bind("id", accountId).bind("email", email).bind("hash", hash)
                .bind("name", "双标识测试").bind("role", role).then().block();
        if (username != null) {
            db.sql("INSERT INTO account_username(account_id, username) VALUES (CAST(:id AS uuid), :name)")
                    .bind("id", accountId).bind("name", username).then().block();
        }
        return accountId;
    }

    private WebTestClient.ResponseSpec login(String identifier, String password) {
        return client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"" + identifier + "\",\"password\":\"" + password + "\"}")
                .exchange();
    }
}
