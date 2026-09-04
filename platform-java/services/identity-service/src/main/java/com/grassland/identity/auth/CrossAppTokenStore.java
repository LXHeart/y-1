package com.grassland.identity.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
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
 * GuestTrialRateLimiter），nonce 是同场景直接先例。签发 {@code SETNX + TTL} 绑定 accountId；核销
 * {@code GETDEL} 原子单次——重放、过期、不存在同归 empty。
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

	/** 签发一次性 token（绑定 accountId，TTL 内有效）。 */
	public Mono<String> issue(String accountId) {
		return issue(accountId, MAX_ISSUE_ATTEMPTS);
	}

	private Mono<String> issue(String accountId, int attemptsLeft) {
		if (attemptsLeft <= 0) {
			return Mono.error(new CrossAppTokenStoreException());
		}
		return Mono.defer(() -> {
			String token = generate();
			return redis.opsForValue().setIfAbsent(keyPrefix + token, accountId, ttl).defaultIfEmpty(Boolean.FALSE)
					.flatMap(stored -> Boolean.TRUE.equals(stored)
							? Mono.just(token)
							: issue(accountId, attemptsLeft - 1))
					.onErrorResume(e -> Mono.error(new CrossAppTokenStoreException()));
		});
	}

	/**
	 * 原子核销：返回绑定的 accountId；不存在/已核销/已过期为 empty Mono。 GETDEL 是单命令原子操作，天然拒绝并发重放。
	 */
	public Mono<String> exchange(String token) {
		return redis.opsForValue().getAndDelete(keyPrefix + token)
				.onErrorResume(e -> Mono.error(new CrossAppTokenStoreException()));
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
