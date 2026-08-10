package com.grassland.identity.notify.external;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.organization.CurrentAccountResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/me/notification-endpoints")
public class NotificationEndpointController {
    private static final Pattern E164 = Pattern.compile("\\+[1-9][0-9]{7,14}");
    private static final Set<String> PUSH_PROVIDERS = Set.of("fcm", "apns", "huawei", "expo");
    private static final Set<String> CATEGORIES = Set.of(
            "invitation", "permission", "engagement", "dispute", "wallet", "system");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CurrentAccountResolver accounts;
    private final ExternalDeliveryRepository repository;
    private final ExternalDeliveryProperties properties;
    private final TransactionalOperator transactions;

    public NotificationEndpointController(
            CurrentAccountResolver accounts,
            ExternalDeliveryRepository repository,
            ExternalDeliveryProperties properties,
            TransactionalOperator transactions) {
        this.accounts = accounts;
        this.repository = repository;
        this.properties = properties;
        this.transactions = transactions;
    }

    @PostMapping("/push")
    public Mono<ResponseEntity<Map<String, Object>>> registerPush(
            @RequestBody PushRequest body, ServerHttpRequest request) {
        return accounts.resolve(request).flatMap(account -> {
            String provider = requirePushProvider(body == null ? null : body.provider());
            String token = requireToken(body == null ? null : body.token());
            return repository.upsertEndpoint(account.id(), "push", token, provider)
                    .thenReturn(ok(Map.of("registered", true)));
        });
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> disable(
            @PathVariable String id, ServerHttpRequest request) {
        UUID endpointId = parseUuid(id, "终端 ID");
        return accounts.resolve(request).flatMap(account -> repository.disableEndpoint(account.id(), endpointId)
                .map(updated -> ok(Map.of("disabled", updated > 0))));
    }

    @PutMapping("/preferences/{category}")
    public Mono<ResponseEntity<Map<String, Object>>> preference(
            @PathVariable String category,
            @RequestBody PreferenceRequest body,
            ServerHttpRequest request) {
        String normalized = category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
        if (!CATEGORIES.contains(normalized) || body == null) {
            throw new IdentityException(400, "通知偏好无效");
        }
        return accounts.resolve(request)
                .flatMap(account -> repository.setPreference(
                                account.id(), normalized, body.pushEnabled(), body.smsEnabled())
                        .thenReturn(ok(Map.of("updated", true))));
    }

    @PostMapping("/sms/challenges")
    public Mono<ResponseEntity<Map<String, Object>>> requestSmsVerification(
            @RequestBody SmsChallengeRequest body, ServerHttpRequest request) {
        String phone = requirePhone(body == null ? null : body.phone());
        String secret = requireChallengeSecret();
        return accounts.resolve(request).flatMap(account -> repository.hasRecentChallenge(account.id())
                .flatMap(recent -> {
                    if (recent) {
                        return Mono.error(new IdentityException(429, "验证码发送过于频繁"));
                    }
                    UUID id = UUID.randomUUID();
                    String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
                    String hash = hash(secret, id, code);
                    ExternalDeliveryRepository.Message message = new ExternalDeliveryRepository.Message(
                            "sms-challenge:" + id, account.id(), "sms", phone, "verification",
                            "草场手机号验证", "您的验证码是：" + code + "，5分钟内有效。",
                            null, "system");
                    return transactions.transactional(repository.createChallenge(id, account.id(), phone, hash)
                                    .then(repository.append(message)))
                            .thenReturn(ResponseEntity.status(202).body(Map.of(
                                    "success", true, "data", Map.of("challengeId", id.toString()))));
                }));
    }

    @PostMapping("/sms/challenges/{id}/confirm")
    public Mono<ResponseEntity<Map<String, Object>>> confirmSmsVerification(
            @PathVariable String id, @RequestBody SmsConfirmRequest body, ServerHttpRequest request) {
        UUID challengeId = parseUuid(id, "验证码请求 ID");
        String code = body == null ? null : body.code();
        if (code == null || !code.matches("[0-9]{6}")) {
            throw new IdentityException(400, "验证码无效");
        }
        String secret = requireChallengeSecret();
        return accounts.resolve(request).flatMap(account -> transactions.transactional(
                        repository.findChallengeForUpdate(challengeId, account.id())
                                .switchIfEmpty(Mono.error(new IdentityException(400, "验证码已失效")))
                                .flatMap(challenge -> {
                                    boolean matches = MessageDigest.isEqual(
                                            challenge.codeHash().getBytes(StandardCharsets.US_ASCII),
                                            hash(secret, challengeId, code).getBytes(StandardCharsets.US_ASCII));
                                    if (!matches) {
                                        return repository.recordChallengeFailure(challengeId).thenReturn(false);
                                    }
                                    return repository.markChallengeVerified(challengeId)
                                            .then(repository.upsertEndpoint(
                                                    account.id(), "sms", challenge.phone(), "gateway"))
                                            .thenReturn(true);
                                })))
                .flatMap(matches -> matches
                        ? Mono.just(ok(Map.of("verified", true)))
                        : Mono.error(new IdentityException(400, "验证码错误")));
    }

    private String requireChallengeSecret() {
        String secret = properties.challengeSecret();
        if (secret == null || secret.length() < 32) {
            throw new IdentityException(503, "短信验证密钥未配置");
        }
        return secret;
    }

    private static String hash(String secret, UUID id, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((id + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("cannot hash SMS verification code", error);
        }
    }

    private static String requirePhone(String value) {
        String phone = value == null ? "" : value.trim();
        if (!E164.matcher(phone).matches()) {
            throw new IdentityException(400, "手机号必须是 E.164 格式");
        }
        return phone;
    }

    private static String requireToken(String value) {
        String token = value == null ? "" : value.trim();
        if (token.length() < 20 || token.length() > 4096) {
            throw new IdentityException(400, "Push token 无效");
        }
        return token;
    }

    private static String requirePushProvider(String value) {
        String provider = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!PUSH_PROVIDERS.contains(provider)) {
            throw new IdentityException(400, "Push provider 无效");
        }
        return provider;
    }

    private static UUID parseUuid(String value, String label) {
        try {
            return UUID.fromString(value);
        } catch (Exception error) {
            throw new IdentityException(400, label + "无效");
        }
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handle(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    public record PushRequest(String provider, String token) {}
    public record PreferenceRequest(boolean pushEnabled, boolean smsEnabled) {}
    public record SmsChallengeRequest(String phone) {}
    public record SmsConfirmRequest(String code) {}
}

