package com.grassland.identity.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 跨应用一次性免登 token（任务书 #76 卡 A）：Redis nonce，不建表。
 *
 * <p>
 * Redis 在本项目的既有定位就是防重放与游客限流（RedisAssertionReplayGuard /
 * GuestTrialRateLimiter），nonce 是同场景直接先例。签发 {@code SETNX + TTL} 绑定载荷
 * {@code accountId|source|audience}（任务书 #86：token 绑定目标应用，管道分隔——UUID 不含
 * 管道符，格式自证，不引入 JSON 序列化器依赖）；核销 {@code GETDEL} 原子单次——重放、过期、
 * 不存在、载荷解析失败（含旧格式裸 accountId 部署窗口残值）同归 empty。
 *
 * <p>
 * 安全边界：Redis 不可用时 fail-closed（抛 {@link CrossAppTokenStoreException}， 端点映射
 * 503），绝不退化为「无防重放的放行」。
 */
@Component
public class CrossAppTokenStore {
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int TOKEN_BYTES = 32;
	/** 32 字节 base64url（无填充）恒为 43 字符；区间留余量只做形态门禁。 */
	private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{40,60}");
	/** 随机 256 位 token 撞键概率可忽略；上限只防异常场景下的无限递归。 */
	private static final int MAX_ISSUE_ATTEMPTS = 3;

	private final ReactiveStringRedisTemplate redis;
	private final Duration ttl;
	private final String keyPrefix;

	public CrossAppTokenStore(ReactiveStringRedisTemplate redis,
			@Value("${identity.cross-app-token.ttl-seconds:300}") long ttlSeconds,
			@Value("${identity.cross-app-token.key-prefix:grassland:auth:cross-app-token:}") String keyPrefix) {
		this.redis = redis;
		this.ttl = Duration.ofSeconds(ttlSeconds);
		this.keyPrefix = keyPrefix;
	}

	long ttlSeconds() {
		return ttl.toSeconds();
	}

	/** 免登 token 载荷（任务书 #86）：audience=目标应用（必匹配），source=签发来源（仅溯源）。 */
	public record CrossAppTokenPayload(String accountId, String source, String audience) {
	}

	/** 签发一次性 token（绑定 accountId|source|audience 载荷，TTL 内有效）。 */
	public Mono<String> issue(String accountId, String source, String audience) {
		return issue(accountId, source, audience, MAX_ISSUE_ATTEMPTS);
	}

	private Mono<String> issue(String accountId, String source, String audience, int attemptsLeft) {
		if (attemptsLeft <= 0) {
			return Mono.error(new CrossAppTokenStoreException());
		}
		return Mono.defer(() -> {
			String token = generate();
			return redis.opsForValue().setIfAbsent(keyPrefix + token, encodePayload(accountId, source, audience), ttl)
					.defaultIfEmpty(Boolean.FALSE)
					.flatMap(stored -> Boolean.TRUE.equals(stored)
							? Mono.just(token)
							: issue(accountId, source, audience, attemptsLeft - 1))
					.onErrorResume(e -> Mono.error(new CrossAppTokenStoreException()));
		});
	}

	/**
	 * 原子核销：返回绑定载荷；不存在/已核销/已过期/载荷无法解析为 empty Mono。 GETDEL
	 * 是单命令原子操作，天然拒绝并发重放。
	 */
	public Mono<CrossAppTokenPayload> exchange(String token) {
		return redis.opsForValue().getAndDelete(keyPrefix + token)
				.flatMap(value -> Mono.justOrEmpty(parsePayload(value)))
				.onErrorResume(e -> Mono.error(new CrossAppTokenStoreException()));
	}

	/** 载荷编码：{@code accountId|source|audience}（调用方保证三参非空，不做防御）。 */
	static String encodePayload(String accountId, String source, String audience) {
		return accountId + "|" + source + "|" + audience;
	}

	/** 载荷解析：null / 非 3 段 / 任一段空白（含旧格式裸 accountId）→ empty。 */
	static Optional<CrossAppTokenPayload> parsePayload(String value) {
		if (value == null) {
			return Optional.empty();
		}
		String[] parts = value.split("\\|");
		if (parts.length != 3) {
			return Optional.empty();
		}
		for (String part : parts) {
			if (part == null || part.isBlank()) {
				return Optional.empty();
			}
		}
		return Optional.of(new CrossAppTokenPayload(parts[0], parts[1], parts[2]));
	}

	/** 形态门禁：不合形态的 token 直接拒（不触 Redis，防任意键注入探测）。 */
	static boolean wellFormed(String token) {
		return token != null && TOKEN_FORMAT.matcher(token).matches();
	}

	static String generate() {
		byte[] bytes = new byte[TOKEN_BYTES];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
