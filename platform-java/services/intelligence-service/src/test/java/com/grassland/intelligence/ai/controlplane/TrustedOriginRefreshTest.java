package com.grassland.intelligence.ai.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.security.IntelligenceException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class TrustedOriginRefreshTest {

	private final PlatformTrustedOriginRepository repository = mock(PlatformTrustedOriginRepository.class);
	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
	private final MutableClock clock = new MutableClock();
	private final TrustedOriginService service = new TrustedOriginService(repository, null, event -> {
	}, clock, meters, 30);

	@Test
	void concurrentSubscribersShareOneQueryAndNextRefreshReadsRevocation() throws Exception {
		var result = Sinks.<String>one();
		var queries = new AtomicInteger();
		when(repository.listEnabledOrigins()).thenReturn(Flux.defer(() -> {
			queries.incrementAndGet();
			return result.asMono().flux();
		}));
		var first = service.refresh().toFuture();
		var second = service.refresh().toFuture();
		assertThat(queries).hasValue(1);
		result.tryEmitValue("https://example.com").orThrow();
		first.get(5, TimeUnit.SECONDS);
		second.get(5, TimeUnit.SECONDS);
		assertThat(service.enabledOrigins()).containsExactly("https://example.com:443");

		when(repository.listEnabledOrigins()).thenReturn(Flux.empty());
		service.refresh().block(Duration.ofSeconds(5));
		assertThat(service.enabledOrigins()).isEmpty();
	}

	@Test
	void slowQueryUsesReadStartSoRevocationCannotBeExtendedBeyondMaxAge() throws Exception {
		var result = Sinks.<String>one();
		when(repository.listEnabledOrigins()).thenReturn(result.asMono().flux());
		var pending = service.refresh().toFuture();
		clock.advance(29);
		result.tryEmitValue("https://example.com").orThrow();
		pending.get(5, TimeUnit.SECONDS);
		assertThat(service.snapshotAgeSeconds()).isEqualTo(29);
		clock.advance(1);
		assertPolicyUnavailable();
	}

	@Test
	void queryAtMaxAgeDoesNotInstallItsStaleResult() throws Exception {
		var result = Sinks.<String>one();
		when(repository.listEnabledOrigins()).thenReturn(result.asMono().flux());
		var pending = service.refresh().toFuture();
		clock.advance(30);
		result.tryEmitValue("https://example.com").orThrow();
		pending.get(5, TimeUnit.SECONDS);
		assertThat(service.snapshotAgeSeconds()).isEqualTo(-1);
		assertPolicyUnavailable();
	}

	@Test
	void stuckQueryTimesOutAndReleasesTheSlotForRecovery() {
		when(repository.listEnabledOrigins()).thenReturn(Flux.never());
		StepVerifier.withVirtualTime(service::refresh).expectSubscription().thenAwait(Duration.ofSeconds(30))
				.expectError(IllegalStateException.class).verify();
		when(repository.listEnabledOrigins()).thenReturn(Flux.empty());
		service.refresh().block(Duration.ofSeconds(5));
		assertThat(service.enabledOrigins()).isEmpty();
		assertThat(meters.get("intelligence.trusted-origin.refresh").counter().count()).isEqualTo(1);
	}

	@Test
	void refreshFailureRetainsOnlyUnexpiredSnapshotAndAllowsAnotherAttempt() {
		when(repository.listEnabledOrigins()).thenReturn(Flux.just("https://example.com"));
		service.refresh().block(Duration.ofSeconds(5));
		when(repository.listEnabledOrigins()).thenReturn(Flux.error(new IllegalStateException("offline")));
		assertThatThrownBy(() -> service.refresh().block(Duration.ofSeconds(5)))
				.isInstanceOf(IllegalStateException.class);
		assertThat(service.enabledOrigins()).hasSize(1);
		clock.advance(30);
		assertPolicyUnavailable();
		when(repository.listEnabledOrigins()).thenReturn(Flux.empty());
		service.refresh().block(Duration.ofSeconds(5));
		assertThat(service.enabledOrigins()).isEmpty();
	}

	@Test
	void unobservedRefreshDoesNotBlockLaterSubscribersAndMetricsExistBeforeFailure() {
		Mono<Void> unused = service.refresh();
		assertThat(unused).isNotNull();
		assertPolicyUnavailable();
		assertThat(meters.get("intelligence.trusted-origin.checks").counter().count()).isEqualTo(1);
		when(repository.listEnabledOrigins()).thenReturn(Flux.empty());
		service.refresh().block(Duration.ofSeconds(5));
		assertThat(service.enabledOrigins()).isEmpty();
	}

	private void assertPolicyUnavailable() {
		assertThatThrownBy(service::enabledOrigins).isInstanceOfSatisfying(IntelligenceException.class, error -> {
			assertThat(error.status()).isEqualTo(503);
			assertThat(error.code()).isEqualTo("policy_unavailable");
		});
	}

	private static class MutableClock extends Clock {
		private Instant now = Instant.parse("2026-09-16T00:00:00Z");

		void advance(long seconds) {
			now = now.plusSeconds(seconds);
		}
		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}
		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
		@Override
		public Instant instant() {
			return now;
		}
	}
}
