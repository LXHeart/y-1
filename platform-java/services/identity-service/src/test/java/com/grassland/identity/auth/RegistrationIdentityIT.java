package com.grassland.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class RegistrationIdentityIT extends IdentityItSupport {

    @Test
    void registrationCreatesSelectedIdentityProfile() {
        assertRegistrationCreatesIdentity("recommender");
        assertRegistrationCreatesIdentity("merchant");
    }

    @Test
    void registrationWithoutIdentityCreatesBareAccount() {
        // 注册不区分身份：缺 initialIdentity = 只建统一账号，身份档案为空（登录后在工作台开通）
        String email = uniqueEmail("bare");
        String code = "654321";
        seedCode(email, code);

        register(email, code, null)
                .expectStatus().isCreated()
                .expectHeader().exists("Set-Cookie")
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.user.email").isEqualTo(email);

        String accountId = db.sql("SELECT id::text FROM app_users WHERE email = :email")
                .bind("email", email)
                .map(row -> row.get(0, String.class)).one().block();
        Long profileCount = db.sql("SELECT count(*) FROM identity_profile WHERE account_id = CAST(:id AS uuid)")
                .bind("id", accountId)
                .map(row -> row.get(0, Long.class)).one().block();
        assertThat(profileCount).isZero();
    }

    @Test
    void registrationRejectsUnknownInitialIdentityBeforeCreatingAccount() {
        String unknownEmail = uniqueEmail("unknown");
        seedCode(unknownEmail, "123456");
        register(unknownEmail, "123456", "consumer")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("初始身份仅支持商家或推荐官");
        assertThat(accountCount(unknownEmail)).isZero();
    }

    private void assertRegistrationCreatesIdentity(String identityType) {
        String email = uniqueEmail(identityType);
        String code = "654321";
        seedCode(email, code);

        register(email, code, identityType)
                .expectStatus().isCreated()
                .expectHeader().exists("Set-Cookie")
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.user.email").isEqualTo(email);

        String accountId = db.sql("SELECT id::text FROM app_users WHERE email = :email")
                .bind("email", email)
                .map(row -> row.get(0, String.class)).one().block();
        String storedType = db.sql("SELECT identity_type FROM identity_profile WHERE account_id = CAST(:id AS uuid)")
                .bind("id", accountId)
                .map(row -> row.get(0, String.class)).one().block();
        Long profileCount = db.sql("SELECT count(*) FROM identity_profile WHERE account_id = CAST(:id AS uuid)")
                .bind("id", accountId)
                .map(row -> row.get(0, Long.class)).one().block();

        assertThat(storedType).isEqualTo(identityType);
        assertThat(profileCount).isEqualTo(1L);
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec register(
            String email, String code, String initialIdentity) {
        String identityField = initialIdentity == null
                ? ""
                : ",\"initialIdentity\":\"" + initialIdentity + "\"";
        return client().post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"" + email + "\",\"password\":\"correct-pass\","
                        + "\"confirmPassword\":\"correct-pass\",\"displayName\":\"New User\","
                        + "\"verificationCode\":\"" + code + "\"" + identityField + "}")
                .exchange();
    }

    private void seedCode(String email, String code) {
        db.sql("INSERT INTO email_verification_codes(email, code, expires_at) "
                        + "VALUES (:email, :code, now() + interval '10 minutes')")
                .bind("email", email)
                .bind("code", code)
                .then().block();
    }

    private long accountCount(String email) {
        return db.sql("SELECT count(*) FROM app_users WHERE email = :email")
                .bind("email", email)
                .map(row -> row.get(0, Long.class)).one().block();
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
