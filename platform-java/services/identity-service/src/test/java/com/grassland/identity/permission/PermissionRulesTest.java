package com.grassland.identity.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.identity.organization.PermissionTier;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** D-05 规则单测：额度策略 + SLA 计算 + 行业/材料枚举。 */
class PermissionRulesTest {

    @Test
    void quotaPerTier() {
        assertThat(PermissionQuotaPolicy.quotaFor(PermissionTier.DRAFT))
                .isEqualTo(new PermissionQuotaPolicy.TierQuota(0, 0, 0));
        assertThat(PermissionQuotaPolicy.quotaFor(PermissionTier.BASIC_PUBLISH))
                .isEqualTo(new PermissionQuotaPolicy.TierQuota(5, 20, 0));
        assertThat(PermissionQuotaPolicy.quotaFor(PermissionTier.FINANCE_TRANSACTION))
                .isEqualTo(new PermissionQuotaPolicy.TierQuota(50, 500, 10_000_000));
    }

    @Test
    void slaDeadlineIsSubmittedPlusWindow() {
        PermissionSla sla = new PermissionSla(72, 12);
        Instant submitted = Instant.parse("2026-07-24T00:00:00Z");
        assertThat(sla.deadlineFor(submitted)).isEqualTo(submitted.plus(Duration.ofHours(72)));
    }

    @Test
    void slaStatusTransitions() {
        PermissionSla sla = new PermissionSla(72, 12);
        Instant submitted = Instant.parse("2026-07-24T00:00:00Z");
        Instant deadline = sla.deadlineFor(submitted); // +72h
        // within（远早于 deadline）
        assertThat(sla.status(PermissionRequestStatus.PENDING, deadline, submitted)).isEqualTo("within");
        // at_risk（进入截止前 12h 内：deadline-11h）
        assertThat(sla.status(PermissionRequestStatus.PENDING, deadline, deadline.minus(Duration.ofHours(11)))).isEqualTo("at_risk");
        // overdue（过 deadline）
        assertThat(sla.status(PermissionRequestStatus.PENDING, deadline, deadline.plus(Duration.ofMinutes(1)))).isEqualTo("overdue");
    }

    @Test
    void slaStatusCompletedForTerminal() {
        PermissionSla sla = new PermissionSla(72, 12);
        Instant deadline = Instant.parse("2026-07-24T00:00:00Z").plus(Duration.ofHours(72));
        assertThat(sla.status(PermissionRequestStatus.APPROVED, deadline, deadline.plus(Duration.ofDays(3)))).isEqualTo("completed");
        assertThat(sla.status(PermissionRequestStatus.REJECTED, deadline, Instant.now())).isEqualTo("completed");
    }

    @Test
    void industryParsingAndRegulated() {
        assertThat(Industry.fromDb(null)).isEqualTo(Industry.OTHER);
        assertThat(Industry.fromDb("  ")).isEqualTo(Industry.OTHER);
        assertThat(Industry.fromDb("EDUCATION")).isEqualTo(Industry.EDUCATION);
        assertThat(Industry.EDUCATION.requiresIndustryLicense()).isTrue();
        assertThat(Industry.BEAUTY.requiresIndustryLicense()).isTrue();
        assertThat(Industry.RETAIL.requiresIndustryLicense()).isFalse();
        assertThatThrownBy(() -> Industry.fromDb("unknown")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void materialTypeParsing() {
        assertThat(MaterialType.fromDb("business_license")).isEqualTo(MaterialType.BUSINESS_LICENSE);
        assertThat(MaterialType.fromDb("CONTACT_INFO")).isEqualTo(MaterialType.CONTACT_INFO);
        assertThatThrownBy(() -> MaterialType.fromDb("nope")).isInstanceOf(IllegalArgumentException.class);
    }
}
