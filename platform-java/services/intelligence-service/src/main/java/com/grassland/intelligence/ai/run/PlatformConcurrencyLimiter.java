package com.grassland.intelligence.ai.run;

import com.grassland.intelligence.ai.PlatformModelConfig;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Cluster-wide concurrency guard for versioned platform model configurations. */
@Component
public final class PlatformConcurrencyLimiter {

    private static final Logger logger = LoggerFactory.getLogger(PlatformConcurrencyLimiter.class);

    private final PlatformConcurrencyLeaseRepository repository;
    private final Duration leaseTtl;

    @Autowired
    public PlatformConcurrencyLimiter(
            PlatformConcurrencyLeaseRepository repository,
            PlatformModelConfig defaults,
            @Value("${ai.platform-model.lease-ttl:PT3M}") Duration leaseTtl) {
        if (leaseTtl.compareTo(defaults.readTimeout()) <= 0) {
            throw new IllegalArgumentException("平台模型 lease TTL 必须大于 provider read timeout");
        }
        this.repository = repository;
        this.leaseTtl = leaseTtl;
    }

    public Mono<Lease> acquire(ProviderResolution provider) {
        if (!provider.isPlatform() || provider.maxConcurrency() == null) {
            return Mono.just(Lease.unlimited());
        }
        if (provider.platformConfigId() == null) {
            return Mono.error(new IntelligenceException(503, "平台模型配置缺少并发资源标识"));
        }
        return repository.acquire(provider.platformConfigId(), leaseTtl)
                .map(slot -> new Lease(repository, slot))
                .switchIfEmpty(Mono.error(new IntelligenceException(429, "平台模型当前并发已满，请稍后重试")));
    }

    public static final class Lease {
        private final PlatformConcurrencyLeaseRepository repository;
        private final PlatformConcurrencyLeaseRepository.SlotLease slot;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(
                PlatformConcurrencyLeaseRepository repository,
                PlatformConcurrencyLeaseRepository.SlotLease slot) {
            this.repository = repository;
            this.slot = slot;
        }

        private static Lease unlimited() {
            return new Lease(null, null);
        }

        /** Best-effort release; TTL is the crash/repository-failure fallback. */
        public Mono<Void> release() {
            if (repository == null || !released.compareAndSet(false, true)) {
                return Mono.empty();
            }
            return repository.release(slot)
                    .doOnNext(releasedNow -> {
                        if (!releasedNow) {
                            logger.debug("Platform concurrency lease already expired or replaced: {}", slot.configId());
                        }
                    })
                    .onErrorResume(error -> {
                        logger.warn("Platform concurrency lease release failed for config {}", slot.configId(), error);
                        return Mono.empty();
                    })
                    .then();
        }
    }
}
