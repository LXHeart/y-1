package com.grassland.finance.credits;

import com.grassland.finance.security.FinanceException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Finance-authoritative, versioned conversion from provider cost cents to billable credits. */
@ConfigurationProperties(prefix = "credits.cents-policy")
public record CreditsCentsPolicyProperties(
        String version,
        Instant effectiveAt,
        RoundingMode rounding,
        Long centsNumerator,
        Long creditsDenominator,
        Long maxCentsPerOperation) {

    private static final long MAX_CREDITS_PER_OPERATION = 1_000_000L;

    public boolean configured() {
        return version != null && !version.isBlank()
                && effectiveAt != null && rounding != null
                && centsNumerator != null && centsNumerator > 0
                && creditsDenominator != null && creditsDenominator > 0
                && maxCentsPerOperation != null && maxCentsPerOperation > 0;
    }

    public Snapshot requireActive(String expectedVersion) {
        return requireActive(expectedVersion, Clock.systemUTC());
    }

    Snapshot requireActive(String expectedVersion, Clock clock) {
        if (!configured()) {
            throw new FinanceException(503, "credits↔cents 换算政策未配置");
        }
        if (expectedVersion == null || !version.equals(expectedVersion)) {
            throw new FinanceException(409, "credits↔cents 换算政策版本不一致");
        }
        if (effectiveAt.isAfter(Instant.now(clock))) {
            throw new FinanceException(503, "credits↔cents 换算政策尚未生效");
        }
        return new Snapshot(
                version, rounding, centsNumerator, creditsDenominator, maxCentsPerOperation);
    }

    public record Snapshot(
            String version,
            RoundingMode rounding,
            long centsNumerator,
            long creditsDenominator,
            long maxCentsPerOperation) {

        public int creditsFor(long cents) {
            if (cents < 0 || cents > maxCentsPerOperation) {
                throw new FinanceException(400, "AI 成本超出单次 credits↔cents 政策范围");
            }
            try {
                long credits = BigDecimal.valueOf(cents)
                        .multiply(BigDecimal.valueOf(creditsDenominator))
                        .divide(BigDecimal.valueOf(centsNumerator), 0, rounding)
                        .longValueExact();
                if (credits < 0 || credits > MAX_CREDITS_PER_OPERATION) {
                    throw new ArithmeticException("converted credits out of range");
                }
                return Math.toIntExact(credits);
            } catch (ArithmeticException error) {
                throw new FinanceException(400, "credits↔cents 换算结果超出支持范围");
            }
        }
    }
}
