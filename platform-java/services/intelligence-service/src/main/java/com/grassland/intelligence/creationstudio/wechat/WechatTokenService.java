package com.grassland.intelligence.creationstudio.wechat;

import com.grassland.crypto.EnvelopeEncryption;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-19：公众号 access_token 管理。 Redis 加密缓存（key 含账号版本——rotate/version
 * 推进即失效）； 刷新互斥（同 key 在途刷新共享同一上游请求）；Redis 不可用降级为直连（渠道功能仍可用，只损失缓存）。 token
 * 永不出现在日志/响应。
 */
@Component
public class WechatTokenService {

	private static final String KEY_PREFIX = "creation:wechat:token:";
	private final ObjectProvider<ReactiveStringRedisTemplate> redisProvider;
	private final ObjectProvider<EnvelopeEncryption> encryptionProvider;
	private final WechatApiClient client;
	// 同 key 在途刷新表：并发调用共享同一上游请求（TC101-090 单刷新锁），终态即移除
	private final ConcurrentHashMap<String, Mono<String>> inFlightRefreshes = new ConcurrentHashMap<>();

	public WechatTokenService(ObjectProvider<ReactiveStringRedisTemplate> redisProvider,
			ObjectProvider<EnvelopeEncryption> encryptionProvider, WechatApiClient client) {
		this.redisProvider = redisProvider;
		this.encryptionProvider = encryptionProvider;
		this.client = client;
	}

	/** 按账号+版本取 token（缓存→直连→写缓存）；版本失效靠 key 后缀。 */
	public Mono<String> token(WechatAccountRepository.AccountRow account, String plainSecret) {
		ReactiveStringRedisTemplate redis = redisProvider.getIfAvailable();
		EnvelopeEncryption crypto = encryptionProvider.getIfAvailable();
		if (redis == null || crypto == null) {
			return client.fetchAccessToken(account.appId(), plainSecret).map(WechatApiClient.AccessToken::accessToken);
		}
		String key = KEY_PREFIX + account.id() + ":v" + account.version();
		// Redis 故障只影响缓存（§AC101-19）——读/写失败都降级为直连接口；
		// 链空完成也兜底直连：token() 契约为「非空或错误」，调用方 then() 不会误跑成功分支。
		return redis.opsForValue().get(key).flatMap(cached -> Mono.just(decrypt(crypto, cached)))
				.switchIfEmpty(refreshAndCache(redis, crypto, key, account, plainSecret))
				.onErrorResume(error -> client.fetchAccessToken(account.appId(), plainSecret)
						.map(WechatApiClient.AccessToken::accessToken))
				.switchIfEmpty(client.fetchAccessToken(account.appId(), plainSecret)
						.map(WechatApiClient.AccessToken::accessToken));
	}

	/** 单飞：同 key 并发刷新共享同一上游请求（cache 重放值），首个完成写缓存后移除表项。 */
	private Mono<String> refreshAndCache(ReactiveStringRedisTemplate redis, EnvelopeEncryption crypto, String key,
			WechatAccountRepository.AccountRow account, String plainSecret) {
		return inFlightRefreshes
				.computeIfAbsent(key,
						ignored -> Mono
								.defer(() -> client.fetchAccessToken(account.appId(), plainSecret)
										.flatMap(token -> redis.opsForValue()
												.set(key, crypto.encrypt(token.accessToken()),
														Duration.ofSeconds(
																Math.max(60, token.expiresInSeconds() - 300)))
												.thenReturn(token.accessToken())))
								.cache().doFinally(signal -> inFlightRefreshes.remove(key)));
	}

	/** 断开/轮换：显式清除缓存（版本推进也让旧 key 永不再读）。 */
	public Mono<Boolean> invalidate(WechatAccountRepository.AccountRow account) {
		ReactiveStringRedisTemplate redis = redisProvider.getIfAvailable();
		if (redis == null) {
			return Mono.just(false);
		}
		return redis.delete(KEY_PREFIX + account.id() + ":v" + account.version()).map(count -> count > 0)
				.defaultIfEmpty(false).onErrorResume(error -> Mono.just(false));
	}

	private static String decrypt(EnvelopeEncryption crypto, String cached) {
		return crypto.decrypt(cached);
	}
}
