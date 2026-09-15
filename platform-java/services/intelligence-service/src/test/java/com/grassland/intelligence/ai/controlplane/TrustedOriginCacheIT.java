package com.grassland.intelligence.ai.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.security.IntelligenceException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 任务书 #103 C103-20：受信白名单有界刷新（快照时点/陈旧 503/跨副本撤销封闭/空集撤销/配置校验）。
 *
 * <p>
 * 跨副本场景：worker 开启 + 1s 刷新间隔——经 repository 直接删行模拟「另一副本撤销、本副本无写事件」， 断言 ≤maxAge
 * 内收紧。陈旧场景：独立服务实例（worker 关闭语境下手动 refresh）+ 走可控 Clock 推进。
 */
class TrustedOriginCacheIT extends IntelligenceItSupport {

	@DynamicPropertySource
	static void fastRefresh(DynamicPropertyRegistry registry) {
		// 快速刷新让跨副本撤销用例在秒级收敛（maxAge 默认 30s 语义由陈旧用例的独立实例覆盖）。
		registry.add("intelligence.trusted-origin.refresh-interval-ms", () -> "1000");
		registry.add("intelligence.trusted-origin.max-age-seconds", () -> "30");
	}

	@Autowired
	private TrustedOriginService trustedOrigins;

	@Autowired
	private PlatformTrustedOriginRepository repository;

	@Autowired
	private PlatformProviderPolicy policy;

	@Test
	@DisplayName("TC103-20-01/02 快照新鲜时校验通过；表删除（跨副本写）后 ≤maxAge 收紧为不受信")
	void crossReplicaRevocationClosesWithinMaxAge() {
		String origin = "https://replica-" + UUID.randomUUID() + ".example.com";
		repository.create(origin, "跨副本撤销用例", null).block(Duration.ofSeconds(5));
		trustedOrigins.refresh().block(Duration.ofSeconds(5));
		assertThat(policy.validateBaseUrl(origin)).isNotNull();

		// 模拟另一副本：直接删行（无本地写事件）——worker 定时刷新应在 ≤maxAge（此处 1s 间隔 + 余量）内收紧
		db.sql("DELETE FROM platform_trusted_origin WHERE origin = :origin").bind("origin", origin).then()
				.block(Duration.ofSeconds(5));
		Awaitility.await().atMost(Duration.ofSeconds(6)).untilAsserted(() -> {
			// 仍可能拿到旧快照 → 抛 UntrustedPlatformOriginException 的时间点即收紧完成
			assertThatThrownBy(() -> policy.validateBaseUrl(origin))
					.isInstanceOf(UntrustedPlatformOriginException.class);
		});
	}

	@Test
	@DisplayName("TC103-20-05 空集是有效撤销结果：全部删除并刷新后任何 origin 都不受信（非 503）")
	void emptySnapshotIsAValidRevocation() {
		trustedOrigins.refresh().block(Duration.ofSeconds(5));
		db.sql("DELETE FROM platform_trusted_origin").then().block(Duration.ofSeconds(5));
		trustedOrigins.refresh().block(Duration.ofSeconds(5));
		assertThatThrownBy(() -> policy.validateBaseUrl("https://anywhere.example.com"))
				.isInstanceOf(UntrustedPlatformOriginException.class).hasMessageContaining("anywhere.example.com");
		// 空集快照新鲜：enabledOrigins 不抛 503（撤销 ≠ 策略不可用）
		assertThat(trustedOrigins.enabledOrigins()).isEmpty();
	}

	@Test
	@DisplayName("TC103-20-03/E09 快照缺失或 age≥maxAge → 503 policy_unavailable；刷新后恢复")
	void staleOrMissingSnapshotRejectsWith503ThenRecovers() {
		// 独立实例：可控 Clock 推进 maxAge（不真实等待）
		AtomicReference<java.time.Instant> now = new AtomicReference<>(java.time.Instant.now());
		java.time.Clock controlled = new java.time.Clock() {
			@Override
			public java.time.ZoneId getZone() {
				return java.time.ZoneOffset.UTC;
			}

			@Override
			public java.time.Clock withZone(java.time.ZoneId zone) {
				return this;
			}

			@Override
			public java.time.Instant instant() {
				return now.get();
			}
		};
		ApplicationEventPublisher noop = event -> {
		};
		TrustedOriginService service = new TrustedOriginService(repository, null, noop, controlled,
				new SimpleMeterRegistry(), 30);

		// 未加载：503（不是全允许、不是无解释空集）
		assertThatThrownBy(service::enabledOrigins).isInstanceOf(IntelligenceException.class)
				.hasMessageContaining("策略暂不可用");
		IntelligenceException missing = catchIe(service::enabledOrigins);
		assertThat(missing.status()).isEqualTo(503);

		// 加载后新鲜：正常返回
		service.refresh().block(Duration.ofSeconds(5));
		assertThat(service.snapshotAgeSeconds()).isZero();

		// 推进 30s：age ≥ maxAge → 503 策略已过期
		now.set(now.get().plusSeconds(31));
		assertThatThrownBy(service::enabledOrigins).isInstanceOf(IntelligenceException.class)
				.hasMessageContaining("已过期");
		assertThat(catchIe(service::enabledOrigins).status()).isEqualTo(503);

		// 再刷新（Clock 同步推进）恢复
		service.refresh().block(Duration.ofSeconds(5));
		assertThat(service.enabledOrigins()).isNotNull();
	}

	@Test
	@DisplayName("TC103-20-01/E14 刷新配置非法启动失败：间隔 <1s / maxAge < 刷新间隔")
	void invalidRefreshConfigFailsFast() {
		ApplicationEventPublisher noop = event -> {
		};
		TrustedOriginService service = new TrustedOriginService(repository, null, noop, java.time.Clock.systemUTC(),
				new SimpleMeterRegistry(), 30);
		org.assertj.core.api.Assertions.assertThatIllegalStateException()
				.isThrownBy(() -> new TrustedOriginRefreshWorker(service, 500, 30));
		org.assertj.core.api.Assertions.assertThatIllegalStateException()
				.isThrownBy(() -> new TrustedOriginRefreshWorker(service, 5000, 4));
		// 合法配置可构造
		assertThat(new TrustedOriginRefreshWorker(service, 5000, 30).maxAge()).isEqualTo(Duration.ofSeconds(30));
	}

	private static IntelligenceException catchIe(Runnable action) {
		try {
			action.run();
		} catch (IntelligenceException error) {
			return error;
		}
		throw new AssertionError("expected IntelligenceException");
	}
}
