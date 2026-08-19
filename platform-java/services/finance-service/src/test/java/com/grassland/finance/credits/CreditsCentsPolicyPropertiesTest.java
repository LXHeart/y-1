package com.grassland.finance.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.finance.security.FinanceException;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CreditsCentsPolicyPropertiesTest {

    private static final Clock NOW = Clock.fixed(
            Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void convertsCentsUsingConfiguredRounding() {
        var halfUp = policy(RoundingMode.HALF_UP, 100, 1, 10_000)
                .requireActive("v1", NOW);
        var down = policy(RoundingMode.DOWN, 100, 1, 10_000)
                .requireActive("v1", NOW);

        assertThat(halfUp.creditsFor(149)).isEqualTo(1);
        assertThat(halfUp.creditsFor(150)).isEqualTo(2);
        assertThat(down.creditsFor(199)).isEqualTo(1);
    }

    @Test
    void supportsNonUnitConversionRatio() {
        var snapshot = policy(RoundingMode.HALF_UP, 25, 10, 10_000)
                .requireActive("v1", NOW);

        assertThat(snapshot.creditsFor(10)).isEqualTo(4);
    }

    @Test
    void rejectsWrongVersionFuturePolicyAndOperationAboveCap() {
        var active = policy(RoundingMode.HALF_UP, 100, 1, 500);
        assertThatThrownBy(() -> active.requireActive("v2", NOW))
                .isInstanceOfSatisfying(FinanceException.class,
                        error -> assertThat(error.status()).isEqualTo(409));

        var future = new CreditsCentsPolicyProperties(
                "v1", Instant.parse("2027-01-01T00:00:00Z"),
                RoundingMode.HALF_UP, 100L, 1L, 500L);
        assertThatThrownBy(() -> future.requireActive("v1", NOW))
                .isInstanceOfSatisfying(FinanceException.class,
                        error -> assertThat(error.status()).isEqualTo(503));

        var snapshot = active.requireActive("v1", NOW);
        assertThatThrownBy(() -> snapshot.creditsFor(501))
                .isInstanceOfSatisfying(FinanceException.class,
                        error -> assertThat(error.status()).isEqualTo(400));
    }

    private static CreditsCentsPolicyProperties policy(
            RoundingMode rounding, long centsNumerator,
            long creditsDenominator, long maxCents) {
        return new CreditsCentsPolicyProperties(
                "v1", Instant.parse("2026-01-01T00:00:00Z"), rounding,
                centsNumerator, creditsDenominator, maxCents);
    }
}
