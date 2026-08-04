package com.grassland.identity.mobile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.identity.identityprofile.DeviceFingerprint;
import com.grassland.identity.identityprofile.IdentitySessionRepository;
import com.grassland.identity.user.AuthUser;
import com.grassland.identity.user.LegacyUserLookup;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 移动端 refresh token 领域服务（GL-P3-IDENTITY-001，docs/移动端刷新token认证方案设计.md）。
 *
 * <p>v1 决策：<b>不轮换</b>——refresh token 30 天固定，刷新只 touch last_used_at + 重签 access token。
 * 风险由「DB 只存 SHA-256 + 单设备/全量撤销 + 刷新限流 + edge 行复查（撤销即时）」覆盖。
 *
 * <p>access token 的 {@code session_token} claim = refresh_token 行 id：edge-bff 据此复查行存活，
 * identity_session（活动身份）也按它隔离——移动设备与 cookie 会话的多设备语义完全一致。
 */
@Component
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int RAW_TOKEN_BYTES = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final IdentitySessionRepository identitySessions;
    private final LegacyUserLookup users;
    private final AccessTokenIssuer accessTokenIssuer;
    private final TransactionalOperator transactions;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration refreshTtl;
    private final int maxActivePerAccount;

    public RefreshTokenService(RefreshTokenRepository repository,
                               IdentitySessionRepository identitySessions,
                               LegacyUserLookup users,
                               AccessTokenIssuer accessTokenIssuer,
                               TransactionalOperator transactions,
                               @Value("${identity.mobile.refresh-token.ttl-days:30}") long ttlDays,
                               @Value("${identity.mobile.refresh-token.max-active-per-account:10}") int maxActivePerAccount) {
        this.repository = repository;
        this.identitySessions = identitySessions;
        this.users = users;
        this.accessTokenIssuer = accessTokenIssuer;
        this.transactions = transactions;
        this.refreshTtl = Duration.ofDays(ttlDays);
        this.maxActivePerAccount = maxActivePerAccount;
    }

    /** 移动端点整体可用性闸门（secret 未配 = 停用，Web 不受影响）。 */
    public boolean isConfigured() {
        return accessTokenIssuer.isConfigured();
    }

    public record IssuedTokens(String accessToken, String refreshToken, long expiresInSeconds) {}

    /**
     * 登录成功后签发（token 模式）。128B SecureRandom → base64url（无 padding）为明文 refresh token；
     * SHA-256 小写 hex 落库。事务 = INSERT + 封顶 prune（超出上限撤最旧，竞态收敛）。
     */
    public Mono<IssuedTokens> issue(AuthUser user, DeviceFingerprint fingerprint, String deviceName, String deviceInfoRaw) {
        String rawToken = generateRawToken();
        Instant now = Instant.now();
        RefreshToken row = new RefreshToken(
                UUID.randomUUID().toString(),
                user.id(),
                sha256Hex(rawToken),
                fingerprint.deviceId(),
                deviceName,
                null,
                now.plus(refreshTtl),
                null,
                now,
                buildMetadata(deviceInfoRaw));
        return transactions.transactional(
                        repository.insert(row).then(repository.pruneOldestBeyond(user.id(), maxActivePerAccount)))
                .then(Mono.fromCallable(() -> {
                    String accessToken = accessTokenIssuer.issue(
                            user.id(), user.email(), user.role(), fingerprint.deviceId(), row.id());
                    return new IssuedTokens(accessToken, rawToken, accessTokenIssuer.ttlSeconds());
                }));
    }

    /**
     * 刷新：hash 查行 → 门（无行/已撤销/已过期 → empty）→ 账号存在且 active（role/email 取现值）→
     * touch last_used_at → 新签 access token。refresh token 原样回带（不轮换）。
     */
    public Mono<IssuedTokens> refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return Mono.empty();
        }
        return repository.findByTokenHash(sha256Hex(rawRefreshToken))
                .filter(row -> row.active(Instant.now()))
                .flatMap(row -> users.findById(row.accountId())
                        .filter(AuthUser::isActive)
                        .flatMap(account -> repository.touchLastUsed(row.id())
                                .thenReturn(row)
                                .map(ignored -> {
                                    String accessToken = accessTokenIssuer.issue(
                                            account.id(), account.email(), account.role(),
                                            row.deviceFingerprint(), row.id());
                                    return new IssuedTokens(accessToken, rawRefreshToken, accessTokenIssuer.ttlSeconds());
                                })));
    }

    /**
     * 撤销（token 自鉴权）：校验行活跃 → allDevices ? 全撤 : 单撤 → 清该移动 session 的活动身份
     * （镜像 {@code IdentitySessionController.revoke} 的「撤销即真正登出」）。无效 token → empty（401 语义）。
     *
     * @return 撤销行数
     */
    public Mono<Long> revoke(String rawRefreshToken, boolean allDevices) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return Mono.empty();
        }
        return repository.findByTokenHash(sha256Hex(rawRefreshToken))
                .filter(row -> row.active(Instant.now()))
                .flatMap(row -> (allDevices
                                ? repository.revokeAllForAccount(row.accountId())
                                : repository.revokeById(row.id()))
                        .flatMap(count -> identitySessions.deleteByToken(row.id()).thenReturn(count)));
    }

    /** 活跃 refresh token 所属账号（撤销审计用）。无效/已撤销/过期 → empty。 */
    public Mono<String> refreshTokenAccountId(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return Mono.empty();
        }
        return repository.findByTokenHash(sha256Hex(rawRefreshToken))
                .filter(row -> row.active(Instant.now()))
                .map(RefreshToken::accountId);
    }

    /** 账号当前活跃设备（refresh token）清单，新→旧。 */
    public Flux<RefreshToken> listActiveDevices(String accountId) {
        return repository.findActiveByAccount(accountId);
    }

    /** 按行 id 查设备（含已撤销/过期；controller 自行判断归属与存在性）。 */
    public Mono<RefreshToken> findDeviceById(String tokenId) {
        return repository.findById(tokenId);
    }

    /**
     * 按行 id 撤销设备（/api/me/devices 视角）。行不存在/已撤销 → 0；跨账号 → empty（纵深防御，
     * controller 应先 {@link #findDeviceById} 区分 403/404）。
     */
    public Mono<Long> revokeDeviceById(String tokenId, String accountId) {
        return repository.findById(tokenId)
                .flatMap(row -> {
                    if (!row.accountId().equals(accountId)) {
                        return Mono.<Long>empty();
                    }
                    return repository.revokeById(row.id())
                            .flatMap(count -> identitySessions.deleteByToken(row.id()).thenReturn(count));
                });
    }

    static String generateRawToken() {
        byte[] bytes = new byte[RAW_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private String buildMetadata(String deviceInfoRaw) {
        if (deviceInfoRaw == null || deviceInfoRaw.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("deviceInfo", deviceInfoRaw);
            return mapper.writeValueAsString(meta);
        } catch (Exception error) {
            log.warn("failed to serialize refresh token metadata; storing null", error);
            return null;
        }
    }
}
