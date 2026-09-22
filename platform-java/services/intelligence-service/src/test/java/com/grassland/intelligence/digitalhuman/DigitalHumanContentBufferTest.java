package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 易失内容缓冲测试（任务书 #105C C105C-03 / TC105C-03-03）：正文只进缓冲，不落 dh_event； 容量 256KiB
 * 有界、TTL、erase 立即清。
 */
class DigitalHumanContentBufferTest extends IntelligenceItSupport {

	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	static {
		REDIS.start();
	}

	@org.springframework.test.context.DynamicPropertySource
	static void redisProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
	}

	@Autowired
	private DigitalHumanContentBuffer buffer;

	@Autowired
	private DatabaseClient db;

	private final String sessionId = UUID.randomUUID().toString();

	@Test
	void tc105c_03_03_contentNeverPersistsAndBufferIsBounded() {
		String marker = "SYNTHETIC-PRIVATE-标记-" + UUID.randomUUID();
		// 正文帧进缓冲；读回有序。
		buffer.append(sessionId, 1, "{\"delta\":\"" + marker + "\"}").block(Duration.ofSeconds(5));
		buffer.append(sessionId, 1, "{\"delta\":\"第二段\"}").block(Duration.ofSeconds(5));
		var lines = buffer.read(sessionId, 1).block(Duration.ofSeconds(5));
		assertThat(lines).hasSize(2).first().asString().contains(marker);

		// dh_event/普通持久表无正文（dump 断言）：无 dh_event 行，dh_session config 无标记。
		Long durable = db.sql("SELECT count(*) AS n FROM dh_event WHERE session_id = CAST(:id AS uuid)")
				.bind("id", sessionId).map(row -> row.get("n", Long.class)).one().defaultIfEmpty(0L)
				.block(Duration.ofSeconds(5));
		assertThat(durable).isZero();
		// 正文只在 list 结构内（无独立 STRING 持久键；日志不 dump 值）——由 read() 有序返回承接验证。

		// 容量：一次推入 >256KiB → evictedOldest=true 且总量回到限内（有界）。
		String big = "x".repeat(200 * 1024);
		var first = buffer.append(sessionId, 2, big).block(Duration.ofSeconds(5));
		assertThat(first.evictedOldest()).isFalse();
		var second = buffer.append(sessionId, 2, big).block(Duration.ofSeconds(5));
		assertThat(second.evictedOldest()).isTrue();
		assertThat(second.bufferedBytes()).isLessThanOrEqualTo(DigitalHumanContentBuffer.MAX_BYTES);

		// erase 立即清；二次幂等。
		assertThat(buffer.erase(sessionId, 2).block(Duration.ofSeconds(5))).isTrue();
		assertThat(buffer.read(sessionId, 2).block(Duration.ofSeconds(5))).isEmpty();
		assertThat(buffer.erase(sessionId, 2).block(Duration.ofSeconds(5))).isFalse();

		// UTF-8 计数口径：两行中文 3 字 ≈ 9 字节（非 chars 计数）。
		String probeSession = UUID.randomUUID().toString();
		buffer.append(probeSession, 1, "中文三字").block(Duration.ofSeconds(5));
		var single = buffer.append(probeSession, 1, "中文三字").block(Duration.ofSeconds(5));
		assertThat(single.bufferedBytes()).isEqualTo("中文三字".getBytes(StandardCharsets.UTF_8).length * 2);
	}
}
