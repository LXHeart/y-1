package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.Disposable;

/**
 * 任务书 #69 卡D：events.transport=redis 跨实例广播——双实例（不同 instanceId）经同一 Redis
 * 互投帧；回环抑制（自己发布的帧不重复投本地 sink）；终态帧远端收口； redis 不可达时 emit 不抛错（发布失败仅
 * WARN，本地直投不受影响）。 纯构造直测（不起 Spring 上下文），容器风格照 S3GeneratedImageStoreIT。
 */
@DisplayName("Video task event stream redis transport (任务书 #69 卡D)")
@Testcontainers
class VideoTaskEventStreamRedisIT {

	@Container
	static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

	private static ReactiveStringRedisTemplate template() {
		RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(REDIS.getHost(),
				REDIS.getMappedPort(6379));
		LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
		factory.afterPropertiesSet();
		return new ReactiveStringRedisTemplate(factory);
	}

	private static VideoTaskEventStream startedInstance(ReactiveStringRedisTemplate redis) {
		VideoTaskEventStream stream = new VideoTaskEventStream(null, null, null, null, 30, "redis", redis);
		stream.start();
		return stream;
	}

	@Test
	@DisplayName("A emit → B 订阅收到同一帧；A 不重复收（回环抑制）；succeeded 远端收口")
	void crossInstanceBroadcastWithLoopSuppression() {
		VideoTaskEventStream a = startedInstance(template());
		VideoTaskEventStream b = startedInstance(template());
		// pub/sub 不重放：必须等双方 SUBSCRIBE 在服务端生效后才 emit，否则首帧竞态丢失
		awaitChannelSubscribers(2);

		UUID taskId = UUID.randomUUID();
		List<String> aFrames = new CopyOnWriteArrayList<>();
		List<String> bFrames = new CopyOnWriteArrayList<>();
		Disposable aSub = a.live(taskId).subscribe(aFrames::add);
		Disposable bSub = b.live(taskId).subscribe(bFrames::add);

		a.emitPhase(taskId, "composing");
		awaitFrame(aFrames, frame -> frame.contains("composing"));
		awaitFrame(bFrames, frame -> frame.contains("composing"));
		// 回环抑制：A 的 redis 回声不再投第二次（等足广播往返窗口后按帧计数）
		awaitQuiet();
		assertThat(aFrames.stream().filter(frame -> frame.contains("composing")).count()).as("A 本地直投一次，回环抑制后不再重复")
				.isEqualTo(1);
		assertThat(bFrames.stream().filter(frame -> frame.contains("composing")).count()).as("B 仅经 redis 收到一次")
				.isEqualTo(1);

		a.emitComposeProgress(taskId, 40);
		awaitFrame(bFrames, frame -> frame.contains("compose_progress") && frame.contains("40"));

		a.emitPhase(taskId, "succeeded");
		awaitFrame(bFrames, frame -> frame.contains("succeeded"));
		awaitDisposal(bSub, "B 的流应随远端终态 complete 收口");

		aSub.dispose();
		bSub.dispose();
		a.stop();
		b.stop();
	}

	@Test
	@DisplayName("redis 不可达：emit 不抛错（发布失败 WARN 降级），本地 sink 仍收到帧")
	void redisDownDoesNotBreakEmit() {
		RedisStandaloneConfiguration dead = new RedisStandaloneConfiguration("localhost", 1);
		LettuceConnectionFactory deadFactory = new LettuceConnectionFactory(dead);
		deadFactory.afterPropertiesSet();
		VideoTaskEventStream stream = startedInstance(new ReactiveStringRedisTemplate(deadFactory));

		UUID taskId = UUID.randomUUID();
		List<String> frames = new CopyOnWriteArrayList<>();
		Disposable subscription = stream.live(taskId).subscribe(frames::add);

		assertThatCode(() -> stream.emitPhase(taskId, "composing")).doesNotThrowAnyException();
		awaitFrame(frames, frame -> frame.contains("composing"));

		subscription.dispose();
		stream.stop();
	}

	// ---------------- helpers ----------------

	/** 轮询 PUBSUB NUMSUB 直到频道订阅数达到预期（listenTo 的 SUBSCRIBE 握手是异步的）。 */
	private static void awaitChannelSubscribers(long expected) {
		io.lettuce.core.RedisClient probeClient = io.lettuce.core.RedisClient
				.create(io.lettuce.core.RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379)));
		try (io.lettuce.core.api.StatefulRedisConnection<String, String> probe = probeClient.connect()) {
			long deadline = System.currentTimeMillis() + 20_000;
			while (System.currentTimeMillis() < deadline) {
				Long count = probe.sync().pubsubNumsub(VideoTaskEventStream.REDIS_CHANNEL)
						.getOrDefault(VideoTaskEventStream.REDIS_CHANNEL, 0L);
				if (count != null && count >= expected) {
					return;
				}
				try {
					Thread.sleep(200);
				} catch (InterruptedException error) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("await interrupted", error);
				}
			}
		} finally {
			probeClient.shutdown();
		}
		throw new AssertionError("频道订阅未在时限内就绪（期待 " + expected + " 个订阅者）");
	}

	private static void awaitFrame(List<String> frames, java.util.function.Predicate<String> match) {
		long deadline = System.currentTimeMillis() + 20_000;
		while (System.currentTimeMillis() < deadline) {
			if (frames.stream().anyMatch(match)) {
				return;
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("await interrupted", error);
			}
		}
		throw new AssertionError("帧未在时限内到达，期待匹配 " + match + "，实收: " + frames);
	}

	/** 等足 pub/sub 广播往返窗口（回环抑制断言前给回声留到账时间）。 */
	private static void awaitQuiet() {
		try {
			Thread.sleep(Duration.ofMillis(800));
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("await interrupted", error);
		}
	}

	private static void awaitDisposal(Disposable subscription, String message) {
		long deadline = System.currentTimeMillis() + 20_000;
		while (System.currentTimeMillis() < deadline) {
			if (subscription.isDisposed()) {
				return;
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("await interrupted", error);
			}
		}
		throw new AssertionError(message);
	}
}
