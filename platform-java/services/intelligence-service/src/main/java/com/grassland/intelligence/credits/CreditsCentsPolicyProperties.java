package com.grassland.intelligence.credits;

import com.grassland.intelligence.security.IntelligenceException;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-approved credits-to-cents policy metadata used to opt priced AI runs into settlement. */
@ConfigurationProperties(prefix = "credits.cents-policy")
public record CreditsCentsPolicyProperties(
        String version,
        Instant effectiveAt,
        RoundingMode rounding,
        Long centsNumerator,
        Long creditsDenominator,
        Long maxCentsPerOperation) {

    public Optional<String> activeVersion() {
        boolean any = version != null && !version.isBlank()
                || effectiveAt != null || rounding != null
                || centsNumerator != null || creditsDenominator != null
                || maxCentsPerOperation != null;
        if (!any) {
            return Optional.empty();
        }
        boolean complete = version != null && !version.isBlank()
                && effectiveAt != null && rounding != null
                && centsNumerator != null && centsNumerator > 0
                && creditsDenominator != null && creditsDenominator > 0
                && maxCentsPerOperation != null && maxCentsPerOperation > 0;
        if (!complete) {
            throw new IntelligenceException(503, "credits↔cents 换算政策配置不完整");
        }
        if (effectiveAt.isAfter(Instant.now())) {
            throw new IntelligenceException(503, "credits↔cents 换算政策尚未生效");
        }
        return Optional.of(version);
    }
}
