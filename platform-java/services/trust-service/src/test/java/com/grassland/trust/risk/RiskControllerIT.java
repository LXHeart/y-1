package com.grassland.trust.risk;

import static com.grassland.identity.assertion.TestAssertionHelper.userSigner;
import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.trust.TrustItSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 任务书 #53 S1.4：风险调查 cases/signals 列表统一分页信封。
 *
 * <p>锁定：{@code data:{items,total,limit,offset}} 信封、offset 翻页取到第二页、
 * total 与 status/severity 筛选同口径、severity 排序保持（critical 先行）、
 * limit/offset 钳制（≤0→50、&gt;200→200、负→0）、RISK 角色门禁。
 * 直插 risk_case/risk_signal 造数（uniq_active_risk_case_subject：活动案件 subject 唯一，逐行换 subject_ref）。
 */
class RiskControllerIT extends TrustItSupport {

    private void seedCase(String subjectRef, String status, String severity, int score, String createdAt) {
        db.sql("""
                        INSERT INTO risk_case (id, subject_kind, subject_ref, organization_id, status, severity,
                                               score, reason, created_at, updated_at)
                        VALUES (CAST(:id AS uuid), 'account', :subjectRef, CAST(:org AS uuid), :status, :severity,
                                :score, '任务书 #53 风控分页造数', CAST(:createdAt AS timestamptz),
                                CAST(:createdAt AS timestamptz))
                        """)
                .bind("id", UUID.randomUUID().toString())
                .bind("subjectRef", subjectRef)
                .bind("org", UUID.randomUUID().toString())
                .bind("status", status)
                .bind("severity", severity)
                .bind("score", score)
                .bind("createdAt", createdAt)
                .then().block();
    }

    private Map<?, ?> listAsRisk(String query) {
        return (Map<?, ?>) client().get().uri("/api/trust/risk/cases" + query)
                .header("X-Grassland-Identity", signRole(UUID.randomUUID().toString(), "risk"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody()
                .get("data");
    }

    /** 与 DisputeAdminControllerIT 同口径：level2 用户断言 + 后台角色。 */
    private String signRole(String accountId, String role) {
        Instant now = Instant.now();
        return userSigner("edge-bff", "grassland-trust").sign(new IdentityAssertion(
                accountId, null, "sid-" + accountId, null, null,
                "cookie-session", "level2", now, "r", "t",
                "grassland-trust", now, now.plusSeconds(60), null, null, role));
    }

    @Test
    void casesEnvelopePaginatesKeepingSeverityOrder() {
        String prefix = "acct-it-" + UUID.randomUUID();
        seedCase(prefix + "-low", "open", "low", 30, "2026-08-20T10:00:00Z");
        seedCase(prefix + "-mid", "open", "medium", 55, "2026-08-20T10:01:00Z");
        seedCase(prefix + "-crit", "open", "critical", 92, "2026-08-20T10:02:00Z");

        // severity 排序保持（分页不换序）：critical 案件必须排在第一行
        Map<?, ?> first = listAsRisk("?status=open&limit=50");
        List<?> items = (List<?>) first.get("items");
        assertThat(items).isNotEmpty();
        assertThat(((Map<?, ?>) items.get(0)).get("severity")).isEqualTo("critical");
    }

    @Test
    void casesPaginationAndTotalMatchFilters() {
        String prefix = "acct-page-" + UUID.randomUUID();
        seedCase(prefix + "-1", "open", "medium", 50, "2026-08-21T10:00:00Z");
        seedCase(prefix + "-2", "open", "medium", 51, "2026-08-21T10:01:00Z");
        seedCase(prefix + "-3", "resolved", "high", 80, "2026-08-21T10:02:00Z");

        // total 与筛选同口径：status+subject 精确命中各 1 条（库内并行的其它测试数据不影响）
        assertThat(((Number) listAsRisk("?status=open&subjectRef=" + prefix + "-1").get("total")).longValue()).isEqualTo(1L);
        Map<?, ?> resolved = listAsRisk("?status=resolved&subjectRef=" + prefix + "-3");
        assertThat(((Number) resolved.get("total")).longValue()).isEqualTo(1L);
        assertThat((List<?>) resolved.get("items")).hasSize(1);

        // offset 翻页真正跨页：两条 medium（上面种的两行保证全量 ≥2），第二页不重复第一页
        Map<?, ?> page1 = listAsRisk("?severity=medium&limit=1");
        Map<?, ?> page2 = listAsRisk("?severity=medium&limit=1&offset=1");
        assertThat((List<?>) page1.get("items")).hasSize(1);
        assertThat((List<?>) page2.get("items")).hasSize(1);
        assertThat(((Map<?, ?>) ((List<?>) page1.get("items")).get(0)).get("id"))
                .isNotEqualTo(((Map<?, ?>) ((List<?>) page2.get("items")).get(0)).get("id"));
        assertThat(page1.get("total")).isEqualTo(page2.get("total"));
    }

    @Test
    void clampsLimitAndOffsetIntoEnvelopeContract() {
        assertThat(listAsRisk("?limit=0").get("limit")).isEqualTo(50);
        assertThat(listAsRisk("?limit=999").get("limit")).isEqualTo(200);
        assertThat(listAsRisk("?offset=-5").get("offset")).isEqualTo(0);
    }

    @Test
    void signalsEnvelopePaginatesAndClamps() {
        db.sql("""
                        INSERT INTO risk_signal (id, source_kind, source_ref, subject_kind, subject_ref,
                                                 organization_id, rule_code, rule_version, score, severity,
                                                 status, occurred_at)
                        VALUES (CAST(:id AS uuid), 'marketplace', :sourceRef, 'account', :subjectRef,
                                CAST(:org AS uuid), 'merchant_cancelled_engagement', 'v1', 60, 'medium',
                                'open', CAST(:occurredAt AS timestamptz))
                        """)
                .bind("id", UUID.randomUUID().toString())
                .bind("sourceRef", "event-" + UUID.randomUUID())
                .bind("subjectRef", "acct-sig-" + UUID.randomUUID())
                .bind("org", UUID.randomUUID().toString())
                .bind("occurredAt", "2026-08-22T10:00:00Z")
                .then().block();

        Map<?, ?> signals = (Map<?, ?>) client().get().uri("/api/trust/risk/signals?limit=0&offset=-1")
                .header("X-Grassland-Identity", signRole(UUID.randomUUID().toString(), "risk"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody()
                .get("data");
        assertThat(signals.get("limit")).isEqualTo(50);
        assertThat(signals.get("offset")).isEqualTo(0);
        assertThat(signals.get("total")).isNotNull();
    }

    @Test
    void requiresRiskRole() {
        client().get().uri("/api/trust/risk/cases").exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/trust/risk/cases")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant", null, "basic_publish"))
                .exchange().expectStatus().isForbidden();
        client().get().uri("/api/trust/risk/cases")
                .header("X-Grassland-Identity", signRole(UUID.randomUUID().toString(), "risk"))
                .exchange().expectStatus().isOk();
    }
}
