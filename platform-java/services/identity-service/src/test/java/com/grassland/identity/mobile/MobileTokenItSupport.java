package com.grassland.identity.mobile;

import com.grassland.identity.IdentityItSupport;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 移动端 token 认证 IT 基座（GL-P3-IDENTITY-001）。
 *
 * <p>继承 {@link IdentityItSupport} 以复用单例 Postgres + 生产同款 Flyway（V20 建 refresh_token 表），
 * 额外注入 {@code identity.mobile.access-token.secret} 打开移动端能力（未配时全部 503，见 {@code MobileSecretUnsetIT}）。
 */
public abstract class MobileTokenItSupport extends IdentityItSupport {

    protected static final String ACCESS_TOKEN_SECRET = "mobile-access-token-secret-32ch!";
    protected static final String DEVICE_INFO = "{\"os\":\"iOS 18\",\"model\":\"iPhone16\"}";

    @DynamicPropertySource
    static void mobileProps(DynamicPropertyRegistry r) {
        r.add("identity.mobile.access-token.secret", () -> ACCESS_TOKEN_SECRET);
        r.add("identity.mobile.access-token.ttl-seconds", () -> "900");
    }

    /** seed 一个 bcrypt 口令账号，返回 accountId（走真实登录链路用）。 */
    protected String seedUser(String email, String password) {
        String hash = at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(4, password.toCharArray());
        String id = UUID.randomUUID().toString();
        db.sql("INSERT INTO app_users(id, email, password_hash, display_name, role, status) "
                + "VALUES (CAST(:id AS uuid), :email, :hash, 'Mobile User', 'user', 'active')")
                .bind("id", id).bind("email", email).bind("hash", hash)
                .then().block();
        return id;
    }

    /** 走 /api/auth/login 的 token 模式拿一组令牌。 */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> loginForTokens(String email, String password, String deviceName) {
        Map<String, Object> body = client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Device-Info", DEVICE_INFO)
                .header("X-Device-Name", deviceName)
                .bodyValue("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        return (Map<String, Object>) data.get("tokens");
    }

    protected String refreshTokenOf(Map<String, Object> tokens) {
        return (String) tokens.get("refresh_token");
    }

    /** 计数某账号仍有效（未撤销、未过期）的 refresh token 行数。 */
    protected Long activeTokenCount(String accountId) {
        return db.sql("SELECT count(*) FROM refresh_token WHERE account_id = CAST(:id AS uuid) "
                        + "AND revoked_at IS NULL AND expires_at > now()")
                .bind("id", accountId)
                .map(row -> row.get(0, Long.class))
                .one().block();
    }
}
