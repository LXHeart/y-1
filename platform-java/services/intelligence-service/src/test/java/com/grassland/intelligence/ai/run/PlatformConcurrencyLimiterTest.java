package com.grassland.intelligence.ai.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;

@DisplayName("PlatformConcurrencyLimiter (PostgreSQL lease)")
class PlatformConcurrencyLimiterTest extends IntelligenceItSupport {

    @Autowired
    PlatformConcurrencyLeaseRepository leases;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM platform_model_concurrency_slot").then().block();
        db.sql("DELETE FROM platform_model_config").then().block();
    }

    @Test
    @DisplayName("两个服务实例共享同一配置的并发上限")
    void enforcesLimitAcrossInstances() {
        UUID configId = seedConfigWithSlots(1);
        ProviderResolution provider = platform(configId, 1);
        PlatformConcurrencyLimiter first = limiter();
        PlatformConcurrencyLimiter second = limiter();

        PlatformConcurrencyLimiter.Lease held = first.acquire(provider).block();
        StepVerifier.create(second.acquire(provider))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IntelligenceException.class);
                    assertThat(((IntelligenceException) error).status()).isEqualTo(429);
                })
                .verify();

        held.release().block();
        second.acquire(provider).flatMap(PlatformConcurrencyLimiter.Lease::release).block();
    }

    @Test
    @DisplayName("过期租约可重占，旧 token 不能释放新租约")
    void expiredLeaseUsesFencingToken() {
        UUID configId = seedConfigWithSlots(1);
        ProviderResolution provider = platform(configId, 1);
        PlatformConcurrencyLimiter.Lease expired = limiter().acquire(provider).block();
        db.sql("UPDATE platform_model_concurrency_slot SET lease_until=now() - INTERVAL '1 second' "
                + "WHERE config_id=CAST(:id AS uuid)")
                .bind("id", configId.toString()).then().block();

        PlatformConcurrencyLimiter.Lease replacement = limiter().acquire(provider).block();
        expired.release().block();

        assertThat(activeLeaseCount(configId)).isEqualTo(1);
        replacement.release().block();
        assertThat(activeLeaseCount(configId)).isZero();
    }

    @Test
    @DisplayName("相同 provider/model/version 的不同配置 ID 独立限流")
    void isolatesDifferentConfigurationIds() {
        UUID firstId = seedConfigWithSlots(1);
        UUID secondId = seedConfigWithSlots(1);

        PlatformConcurrencyLimiter.Lease first = limiter().acquire(platform(firstId, 1)).block();
        PlatformConcurrencyLimiter.Lease second = limiter().acquire(platform(secondId, 1)).block();

        assertThat(activeLeaseCount(firstId)).isEqualTo(1);
        assertThat(activeLeaseCount(secondId)).isEqualTo(1);
        first.release().then(second.release()).block();
    }

    private PlatformConcurrencyLimiter limiter() {
        return new PlatformConcurrencyLimiter(leases, Duration.ofMinutes(3), Duration.ofMinutes(2));
    }

    private ProviderResolution platform(UUID configId, int maximum) {
        return ProviderResolution.platform(configId, "qwen", QWEN.baseUrl(), "qwen-plus", 1, maximum);
    }

    private UUID seedConfigWithSlots(int maximum) {
        UUID id = db.sql("INSERT INTO platform_model_config(capability, model_role, provider, model, base_url, "
                        + "max_concurrency, health_status, enabled, version) "
                        + "VALUES ('text-' || gen_random_uuid()::text, 'primary', 'qwen', 'qwen-plus', :baseUrl, "
                        + ":maximum, 'healthy', true, 1) RETURNING id::text")
                .bind("baseUrl", QWEN.baseUrl())
                .bind("maximum", maximum)
                .map((row, meta) -> UUID.fromString(row.get("id", String.class))).one().block();
        db.sql("INSERT INTO platform_model_concurrency_slot(config_id, slot_no) "
                        + "SELECT CAST(:id AS uuid), generate_series(1, :maximum)")
                .bind("id", id.toString()).bind("maximum", maximum).then().block();
        return id;
    }

    private Long activeLeaseCount(UUID configId) {
        return db.sql("SELECT COUNT(*) AS n FROM platform_model_concurrency_slot "
                        + "WHERE config_id=CAST(:id AS uuid) AND lease_until > now()")
                .bind("id", configId.toString())
                .map((row, meta) -> row.get("n", Long.class)).one().block();
    }
}
