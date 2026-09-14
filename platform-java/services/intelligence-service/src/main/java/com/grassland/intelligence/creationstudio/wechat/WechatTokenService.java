package com.grassland.intelligence.creationstudio.wechat;

import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-19（§6.8）：公众号 access_token 管理。 Redis 加密缓存（key
 * 含账号版本——rotate/version 推进即失效）；TTL=max(1, expires_in-120) 且上限 7000s；
 * 跨实例刷新互斥（SET NX 锁 TTL 30s，释放校验持有者， 未抢到锁者有界轮询缓存）。 Redis 不可用（缺 bean 或运行时故障）→
 * 渠道操作 503 fail-closed—— 不明文降级， 本地编辑／导出不受影响（AC101-19/TC101-091）。token
 * 永不出现在日志/响应。
 */
@Component
public class WechatTokenService {

	private static final String KEY_PREFIX = "creation:wechat:token:";
	private static final Duration LOCK_TTL = Duration.ofSeconds(30);
	private static final Duration LOCK_POLL_INTERVAL = Duration.ofMillis(250);
	private static final Duration LOCK_WAIT = Duration.ofSeconds(8);
	// 释放锁必须校验持有者（Lua 原子比较删除），避免误删对等实例稍后取得的锁
	private static final RedisScript<Long> UNLOCK_SCRIPT = RedisScript.of(
			"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
			Long.class);

	private final ObjectProvider<ReactiveStringRedisTemplate> redisProvider;
	private final ObjectProvider<EnvelopeEncryption> encryptionProvider;
	private final WechatApiClient client;

	public WechatTokenService(ObjectProvider<ReactiveStringRedisTemplate> redisProvider,
			ObjectProvider<EnvelopeEncryption> encryptionProvider, WechatApiClient client) {
		this.redisProvider = redisProvider;
		this.encryptionProvider = encryptionProvider;
		this.client = client;
	}

	/** 按账号+版本取 token（缓存→持锁刷新→等待对等实例）；Redis 故障一律 503（不直连降级）。 */
	public Mono<String> token(WechatAccountRepository.AccountRow account, String plainSecret) {
		ReactiveStringRedisTemplate redis = redisProvider.getIfAvailable();
		EnvelopeEncryption crypto = encryptionProvider.getIfAvailable();
		if (redis == null || crypto == null) {
			return Mono.error(dependencyUnavailable());
		}
		String key = KEY_PREFIX + account.id() + ":v" + account.version();
		return redis.opsForValue().get(key).flatMap(cached -> Mono.just(crypto.decrypt(cached)))
				.switchIfEmpty(refreshUnderLock(redis, crypto, key, account, plainSecret))
				.onErrorResume(this::asDependencyUnavailable);
	}

	private Mono<String> refreshUnderLock(ReactiveStringRedisTemplate redis, EnvelopeEncryption crypto, String key,
			WechatAccountRepository.AccountRow account, String plainSecret) {
		String lockKey = key + ":lock";
		String holder = UUID.randomUUID().toString();
		return redis.opsForValue().setIfAbsent(lockKey, holder, LOCK_TTL)
				.flatMap(acquired -> Boolean.TRUE.equals(acquired)
						? fetchWriteAndUnlock(redis, crypto, key, lockKey, holder, account, plainSecret)
						: awaitPeerRefresh(redis, crypto, key, System.nanoTime() + LOCK_WAIT.toNanos()));
	}

	private Mono<String> fetchWriteAndUnlock(ReactiveStringRedisTemplate redis, EnvelopeEncryption crypto, String key,
			String lockKey, String holder, WechatAccountRepository.AccountRow account, String plainSecret) {
		return client.fetchAccessToken(account.appId(), plainSecret)
				.flatMap(accessToken -> redis.opsForValue()
						.set(key, crypto.encrypt(accessToken.accessToken()),
								Duration.ofSeconds(Math.min(7000, Math.max(1, accessToken.expiresInSeconds() - 120))))
						.thenReturn(accessToken.accessToken()))
				// 成败都释放锁（校验持有者）；释放失败不吞业务结果，由外层统一归类
				.flatMap(token -> unlock(redis, lockKey, holder).thenReturn(token))
				.onErrorResume(error -> unlock(redis, lockKey, holder).then(Mono.error(error)));
	}

	/** 未抢到锁：对等实例在刷新——有界轮询缓存，超时按依赖不可用返回（不盲打上游）。 */
	private Mono<String> awaitPeerRefresh(ReactiveStringRedisTemplate redis, EnvelopeEncryption crypto, String key,
			long deadlineNanos) {
		return redis.opsForValue().get(key).flatMap(cached -> Mono.just(crypto.decrypt(cached)))
				.switchIfEmpty(Mono.defer(() -> {
					if (System.nanoTime() >= deadlineNanos) {
						return Mono.error(new IntelligenceException(503, "STUDIO_DEPENDENCY_UNAVAILABLE",
								"公众号 token 刷新繁忙，请稍后重试"));
					}
					return Mono.delay(LOCK_POLL_INTERVAL).then(awaitPeerRefresh(redis, crypto, key, deadlineNanos));
				}));
	}

	private Mono<Long> unlock(ReactiveStringRedisTemplate redis, String lockKey, String holder) {
		return redis.execute(UNLOCK_SCRIPT, List.of(lockKey), List.of(holder)).next();
	}

	/** 断开/轮换：显式清除缓存与锁（版本推进也让旧 key 永不再读）。 */
	public Mono<Boolean> invalidate(WechatAccountRepository.AccountRow account) {
		ReactiveStringRedisTemplate redis = redisProvider.getIfAvailable();
		if (redis == null) {
			return Mono.just(false);
		}
		String key = KEY_PREFIX + account.id() + ":v" + account.version();
		return redis.delete(key).map(count -> count > 0).defaultIfEmpty(false).onErrorResume(error -> Mono.just(false));
	}

	private static IntelligenceException dependencyUnavailable() {
		return new IntelligenceException(503, "STUDIO_DEPENDENCY_UNAVAILABLE", "公众号渠道缓存依赖不可用");
	}

	/** Redis 运行时故障 → 503；业务错误（IntelligenceException/微信侧 WechatApiException）原样透出。 */
	private <T> Mono<T> asDependencyUnavailable(Throwable error) {
		if (error instanceof IntelligenceException || error instanceof WechatApiClient.WechatApiException) {
			return Mono.error(error);
		}
		return Mono.error(dependencyUnavailable());
	}
}
