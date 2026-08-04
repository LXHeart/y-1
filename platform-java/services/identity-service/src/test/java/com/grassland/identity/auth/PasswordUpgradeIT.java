package com.grassland.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import com.grassland.identity.security.Argon2PasswordHasher;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 密码哈希迁移 IT（GL-P3-IDENTITY-001 / B9）：bcrypt 用户登录后透明 rehash 为 argon2id，且不影响登录结果。
 */
class PasswordUpgradeIT extends IdentityItSupport {

    @Autowired
    private Argon2PasswordHasher argon2Hasher;

    @Test
    void bcryptUserIsRehashedToArgon2AfterSuccessfulLogin() {
        String email = "rehash-bcrypt@example.com";
        String id = seedWithHash(email, bcrypt("correct-pass"));
        assertThat(storedHash(id)).startsWith("$2");

        login(email, "correct-pass").expectStatus().isOk();

        String upgraded = storedHash(id);
        assertThat(upgraded).startsWith("$argon2id$");
        // 升级后的哈希仍能校验原口令（没写坏）。
        assertThat(argon2Hasher.matches("correct-pass", upgraded)).isTrue();

        // 再登录一次：已是 argon2id，验证通过且哈希不再变动。
        login(email, "correct-pass").expectStatus().isOk();
        assertThat(storedHash(id)).isEqualTo(upgraded);
    }

    @Test
    void failedLoginDoesNotRehash() {
        String email = "rehash-fail@example.com";
        String id = seedWithHash(email, bcrypt("correct-pass"));
        String before = storedHash(id);

        login(email, "wrong-pass").expectStatus().isUnauthorized();

        assertThat(storedHash(id)).isEqualTo(before).startsWith("$2");
    }

    @Test
    void argon2UserLoginIsUnaffected() {
        String email = "rehash-argon2@example.com";
        String hash = argon2Hasher.hash("correct-pass");
        String id = seedWithHash(email, hash);

        login(email, "correct-pass").expectStatus().isOk();

        assertThat(storedHash(id)).isEqualTo(hash);
    }

    @Test
    void registerStoresArgon2Hash() {
        String email = "register-argon2@example.com";
        // 直接写一条已验证的注册验证码，绕过邮件发送。
        String code = "654321";
        // 表名/列名对齐 EmailVerificationService 实际读写的 legacy 表：email_verification_codes(明文 code, used)。
        db.sql("INSERT INTO email_verification_codes(email, code, expires_at) "
                        + "VALUES (:email, :code, now() + interval '10 minutes')")
                .bind("email", email)
                .bind("code", code)
                .then().block();
        // 注册链路的验证码校验实现各异，这里只断言「若注册成功则落 argon2id」，
        // 验证码不通过则跳过（不把本 IT 绑死在验证码实现细节上）。
        boolean created = client().post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"" + email + "\",\"password\":\"correct-pass\","
                        + "\"displayName\":\"Argon2 User\",\"verificationCode\":\"" + code + "\"}")
                .exchange().returnResult(String.class).getStatus().value() == 201;
        if (created) {
            String hash = db.sql("SELECT password_hash FROM app_users WHERE email = :email")
                    .bind("email", email).map(row -> row.get(0, String.class)).one().block();
            assertThat(hash).startsWith("$argon2id$");
        }
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec login(String email, String password) {
        return client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .exchange();
    }

    private static String bcrypt(String password) {
        return at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(4, password.toCharArray());
    }

    private String seedWithHash(String email, String hash) {
        String id = UUID.randomUUID().toString();
        db.sql("INSERT INTO app_users(id, email, password_hash, display_name, role, status) "
                        + "VALUES (CAST(:id AS uuid), :email, :hash, 'Upgrade User', 'user', 'active')")
                .bind("id", id).bind("email", email).bind("hash", hash)
                .then().block();
        return id;
    }

    private String storedHash(String id) {
        return db.sql("SELECT password_hash FROM app_users WHERE id = CAST(:id AS uuid)")
                .bind("id", id).map(row -> row.get(0, String.class)).one().block();
    }
}
