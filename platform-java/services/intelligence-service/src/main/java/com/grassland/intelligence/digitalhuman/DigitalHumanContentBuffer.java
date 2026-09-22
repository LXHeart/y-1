package com.grassland.intelligence.digitalhuman;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 易失内容缓冲（任务书 #105C C105C-03 / K05）：独立 Redis、容量 256KiB、TTL 10 分钟。
 *
 * <p>
 * 键 {@code dh:buffer:{sessionId}:{contentEpoch}}（独立无持久化实例的 prefix 隔离；C03 复用测试
 * Redis 实例、按 K10 语义 prefix 隔离）。正文（assistant.delta 等）只进本缓冲，<b>绝不</b>落
 * dh_event/日志 （TC105C-03-03）。达到 256KiB 淘汰最旧已完成片段（调用方据此标
 * replayComplete=false）；erase 立即清。 日志不 dump 缓冲值。
 */
@Component
public class DigitalHumanContentBuffer {

	/** K05：会话最长 10 分钟 + 终止后 10 分钟；最大 256KiB UTF-8。 */
	static final Duration TTL = Duration.ofMinutes(10);
	static final int MAX_BYTES = 256 * 1024;

	private static final String KEY_PREFIX = "dh:buffer:";

	private final ReactiveStringRedisTemplate redis;

	public DigitalHumanContentBuffer(ReactiveStringRedisTemplate redis) {
		this.redis = redis;
	}

	public record AppendResult(boolean stored, boolean evictedOldest, int bufferedBytes) {
	}

	private String key(String sessionId, long contentEpoch) {
		return KEY_PREFIX + sessionId + ":" + contentEpoch;
	}

	/** 追加一行（JSON 帧）；超容量淘汰最旧行直至回到限内（有界，不无限增内存）。 */
	public Mono<AppendResult> append(String sessionId, long contentEpoch, String frame) {
		String key = key(sessionId, contentEpoch);
		return redis.opsForList().rightPush(key, frame).then(redis.expire(key, TTL)).then(bytes(key))
				.flatMap(total -> total <= MAX_BYTES
						? Mono.just(new AppendResult(true, false, total))
						: trimToBound(key).map(size -> new AppendResult(true, true, size)));
	}

	/** 逐行淘汰最旧内容直至回到上限内（有界；调用方以 evictedOldest 标 replayComplete=false）。 */
	private Mono<Integer> trimToBound(String key) {
		return bytes(key).flatMap(size -> {
			if (size <= MAX_BYTES) {
				return Mono.just(size);
			}
			return redis.opsForList().leftPop(key).then(trimToBound(key));
		}).defaultIfEmpty(0);
	}

	/** 读当前缓冲（顺序 ASC）；键不存在 → 空表。 */
	public Mono<List<String>> read(String sessionId, long contentEpoch) {
		return redis.opsForList().range(key(sessionId, contentEpoch), 0, -1).collectList()
				.defaultIfEmpty(new ArrayList<>());
	}

	/** 立即清除（abort/删除墓碑路径）。 */
	public Mono<Boolean> erase(String sessionId, long contentEpoch) {
		return redis.delete(key(sessionId, contentEpoch)).map(deleted -> deleted > 0).defaultIfEmpty(false);
	}

	private Mono<Integer> bytes(String key) {
		return redis.opsForList().range(key, 0, -1).reduce(0,
				(total, line) -> total + line.getBytes(StandardCharsets.UTF_8).length);
	}
}
