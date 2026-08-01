package com.grassland.identity.notify.mail;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 事务邮件 outbox 配置（GL-P1-NOTIFY-001）。镜像 {@code event/OutboxProperties}，差异：
 * 无 Kafka topic（外部 send 是 SMTP），加 {@code maxAttempts}（死信封顶，领域 outbox 是无限重试）。
 *
 * <p>属性前缀 {@code identity.mail-outbox}。默认 {@code enabled=false}——仅当 SMTP 已配
 * （{@code SmtpMailSender.isConfigured()}）时由装配层置 true；测试环境默认关，避免轮询空发。
 */
@ConfigurationProperties(prefix = "identity.mail-outbox")
public record MailOutboxProperties(
        boolean enabled,
        long pollIntervalMs,
        int batchSize,
        int maxConcurrency,
        long claimLeaseMs,
        int maxAttempts,
        long initialBackoffMs,
        long maxBackoffMs,
        long sendTimeoutMs) {

    public MailOutboxProperties {
        pollIntervalMs = positive(pollIntervalMs, 2_000);
        batchSize = Math.max(batchSize, 1);
        maxConcurrency = Math.max(maxConcurrency, 1);
        claimLeaseMs = positive(claimLeaseMs, 60_000);
        maxAttempts = Math.max(maxAttempts, 1);
        initialBackoffMs = positive(initialBackoffMs, 1_000);
        maxBackoffMs = Math.max(positive(maxBackoffMs, 30_000), initialBackoffMs);
        sendTimeoutMs = positive(sendTimeoutMs, 30_000);
    }

    public Duration claimLease() {
        return Duration.ofMillis(claimLeaseMs);
    }

    public Duration sendTimeout() {
        return Duration.ofMillis(sendTimeoutMs);
    }

    private static long positive(long value, long fallback) {
        return value > 0 ? value : fallback;
    }
}
