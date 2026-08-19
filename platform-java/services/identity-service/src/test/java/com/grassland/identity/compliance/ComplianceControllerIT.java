package com.grassland.identity.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.grassland.identity.IdentityItSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

class ComplianceControllerIT extends IdentityItSupport {

    @Autowired
    private ComplianceRepository repository;

    @MockitoBean
    private ComplianceDomainClient domains;

    @BeforeEach
    void stubComplianceDomains() {
        when(domains.marketplaceCheck(anyString())).thenReturn(Mono.just(ComplianceModels.DomainCheck.empty()));
        when(domains.financeCheck(anyString())).thenReturn(Mono.just(ComplianceModels.DomainCheck.empty()));
        when(domains.trustCheck(anyString(), any())).thenReturn(Mono.just(ComplianceModels.DomainCheck.empty()));
        when(domains.intelligenceCheck(anyString())).thenReturn(Mono.just(ComplianceModels.DomainCheck.empty()));
    }

    @Test
    void createsOneActiveExportAndExposesItsAuditEntry() {
        Seeded account = seedAccount("compliance-export-" + UUID.randomUUID() + "@test.local");

        client().post().uri("/api/me/compliance/exports")
                .header("Cookie", "y1.sid=" + account.cookie())
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.status").isEqualTo("queued")
                .jsonPath("$.data.format").isEqualTo("zip");

        client().post().uri("/api/me/compliance/exports")
                .header("Cookie", "y1.sid=" + account.cookie())
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false);

        client().get().uri("/api/me/compliance/audit?limit=20")
                .header("Cookie", "y1.sid=" + account.cookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.entries[0].action").isEqualTo("export_requested")
                .jsonPath("$.data.entries[0].actorType").isEqualTo("account");
    }

    @Test
    void aggregatesDomainBlockersAndForwardsEngagementReferencesToTrust() {
        Seeded account = seedAccount("compliance-blocked-" + UUID.randomUUID() + "@test.local");
        ComplianceModels.Blocker blocker = new ComplianceModels.Blocker(
                "marketplace", "PENDING_ORDER", "仍有未完成订单", 2, null);
        when(domains.marketplaceCheck(account.accountId())).thenReturn(Mono.just(
                new ComplianceModels.DomainCheck(List.of(blocker), List.of("engagement-1"))));
        when(domains.trustCheck(account.accountId(), List.of("engagement-1")))
                .thenReturn(Mono.just(ComplianceModels.DomainCheck.empty()));

        client().get().uri("/api/me/compliance/closure-check")
                .header("Cookie", "y1.sid=" + account.cookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.eligible").isEqualTo(false)
                .jsonPath("$.data.blockers[0].domain").isEqualTo("marketplace")
                .jsonPath("$.data.blockers[0].code").isEqualTo("PENDING_ORDER")
                .jsonPath("$.data.blockers[0].count").isEqualTo(2);
    }

    @Test
    void eligibleClosureDeletesAccountSessionsAndWritesImmutableFacts() {
        Seeded account = seedAccount("compliance-close-" + UUID.randomUUID() + "@test.local");

        client().post().uri("/api/me/compliance/account-closure")
                .header("Cookie", "y1.sid=" + account.cookie())
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.data.status").isEqualTo("retention")
                .jsonPath("$.data.check.eligible").isEqualTo(true)
                .jsonPath("$.data.existing").isEqualTo(false);

        Map<String, Object> state = db.sql("""
                        SELECT u.status, u.deleted_at,
                               (SELECT COUNT(*) FROM session s
                                WHERE s.sess -> 'user' ->> 'id' = u.id::text) AS sessions,
                               (SELECT COUNT(*) FROM pii_lifecycle_audit a
                                WHERE a.account_id = u.id AND a.action = 'closure_requested') AS audits,
                               (SELECT COUNT(*) FROM outbox o
                                WHERE o.aggregate_id = u.id::text
                                  AND o.event_type = 'AccountClosureRequested') AS events
                        FROM app_users u WHERE u.id = CAST(:id AS uuid)
                        """)
                .bind("id", account.accountId())
                .fetch().one().block();

        assertThat(state).isNotNull();
        assertThat(state.get("status")).isEqualTo("deleted");
        assertThat(state.get("deleted_at")).isNotNull();
        assertThat(((Number) state.get("sessions")).longValue()).isZero();
        assertThat(((Number) state.get("audits")).longValue()).isEqualTo(1);
        assertThat(((Number) state.get("events")).longValue()).isEqualTo(1);

        assertThatThrownBy(() -> db.sql("UPDATE pii_lifecycle_audit SET action = 'tampered'"
                        + " WHERE account_id = CAST(:id AS uuid)")
                .bind("id", account.accountId()).then().block())
                .hasMessageContaining("PII lifecycle audit is immutable");
        assertThatThrownBy(() -> db.sql("DELETE FROM pii_lifecycle_audit"
                        + " WHERE account_id = CAST(:id AS uuid)")
                .bind("id", account.accountId()).then().block())
                .hasMessageContaining("PII lifecycle audit is immutable");
    }

    @Test
    void exportIncludesAccountSettingsAndMembershipsThenRetentionPurgesDirectIdentifiers() {
        Seeded account = seedAccount("compliance-purge-" + UUID.randomUUID() + "@test.local");
        Seeded owner = seedAccount("compliance-owner-" + UUID.randomUUID() + "@test.local");
        String organizationId = createOrg(owner.cookie(), "Compliance Fixture");
        String storeId = UUID.randomUUID().toString();
        String invitationId = UUID.randomUUID().toString();
        String auditId = UUID.randomUUID().toString();

        db.sql("INSERT INTO store(id, organization_id, name) VALUES (CAST(:id AS uuid), CAST(:org AS uuid), 'Test')")
                .bind("id", storeId).bind("org", organizationId).then().block();
        db.sql("INSERT INTO identity_profile(id, account_id, identity_type, status)"
                        + " VALUES (gen_random_uuid(), CAST(:id AS uuid), 'recommender', 'active')")
                .bind("id", account.accountId()).then().block();
        db.sql("INSERT INTO organization_membership(id, organization_id, account_id, role)"
                        + " VALUES (gen_random_uuid(), CAST(:org AS uuid), CAST(:id AS uuid), 'member')")
                .bind("org", organizationId).bind("id", account.accountId()).then().block();
        db.sql("INSERT INTO store_membership(id, store_id, account_id, role)"
                        + " VALUES (gen_random_uuid(), CAST(:store AS uuid), CAST(:id AS uuid), 'staff')")
                .bind("store", storeId).bind("id", account.accountId()).then().block();
        db.sql("INSERT INTO backend_role(account_id, role) VALUES (CAST(:id AS uuid), 'auditor')")
                .bind("id", account.accountId()).then().block();
        db.sql("INSERT INTO user_settings(id, user_id, settings_type, settings_json)"
                        + " VALUES (gen_random_uuid(), CAST(:id AS uuid), 'analysis',"
                        + " '{\"features\":{\"video\":{\"provider\":\"qwen\",\"apiKey\":\"super-secret\"}}}')")
                .bind("id", account.accountId()).then().block();
        db.sql("INSERT INTO email_verification_codes(email, code, expires_at)"
                        + " VALUES (:email, '123456', now() + interval '1 day')")
                .bind("email", email(account.accountId())).then().block();
        db.sql("INSERT INTO organization_invitation(id, organization_id, email, role, status,"
                        + " invited_by_account_id, expires_at) VALUES (CAST(:invitation AS uuid),"
                        + " CAST(:org AS uuid), :email, 'member', 'pending', CAST(:owner AS uuid),"
                        + " now() + interval '1 day')")
                .bind("invitation", invitationId).bind("org", organizationId)
                .bind("email", email(account.accountId())).bind("owner", owner.accountId()).then().block();
        db.sql("INSERT INTO identity_audit_log(id, account_id, action, session_token, device_id,"
                        + " ip_address, user_agent, detail) VALUES (CAST(:audit AS uuid), CAST(:id AS uuid),"
                        + " 'activate', 'secret-session', 'device-1', '203.0.113.10', 'Browser', '{\"ip\":\"raw\"}')")
                .bind("audit", auditId).bind("id", account.accountId()).then().block();
        db.sql("INSERT INTO recommender_verification_request(account_id, materials, status, review_note)"
                        + " VALUES (CAST(:id AS uuid), '{\"portfolio\":\"private\"}', 'approved', 'private note')")
                .bind("id", account.accountId()).then().block();

        String exported = repository.exportIdentityJson(account.accountId()).block();
        assertThat(exported).contains("\"settings\"").contains("\"organizationMemberships\"")
                .contains("\"storeMemberships\"").contains("qwen").contains(storeId)
                .doesNotContain("super-secret");

        repository.softDeleteAccount(account.accountId()).then(repository.purgeLocalPii(account.accountId())).block();

        Map<String, Object> state = db.sql("""
                        SELECT u.email,
                          (SELECT COUNT(*) FROM identity_profile WHERE account_id = u.id) AS identities,
                          (SELECT COUNT(*) FROM organization_membership WHERE account_id = u.id) AS org_memberships,
                          (SELECT COUNT(*) FROM store_membership WHERE account_id = u.id) AS store_memberships,
                          (SELECT COUNT(*) FROM backend_role WHERE account_id = u.id) AS backend_roles,
                          (SELECT COUNT(*) FROM user_settings WHERE user_id = u.id) AS settings,
                          (SELECT COUNT(*) FROM email_verification_codes c
                           WHERE lower(c.email) LIKE 'compliance-purge-%') AS verification_codes,
                          (SELECT COUNT(*) FROM identity_audit_log a WHERE a.account_id = u.id
                           AND (a.session_token IS NOT NULL OR a.device_id IS NOT NULL
                                OR a.ip_address IS NOT NULL OR a.user_agent IS NOT NULL
                                OR a.detail IS NOT NULL)) AS audit_identifiers,
                          (SELECT materials::text FROM recommender_verification_request r
                           WHERE r.account_id = u.id LIMIT 1) AS verification_materials
                        FROM app_users u WHERE u.id = CAST(:id AS uuid)
                        """).bind("id", account.accountId()).fetch().one().block();

        assertThat(state).isNotNull();
        assertThat(state.get("email")).isEqualTo("deleted+" + account.accountId().replace("-", "")
                + "@deleted.invalid");
        for (String field : List.of("identities", "org_memberships", "store_memberships", "backend_roles",
                "settings", "verification_codes", "audit_identifiers")) {
            assertThat(((Number) state.get(field)).longValue()).as(field).isZero();
        }
        assertThat(state.get("verification_materials")).isEqualTo("{}");

        Map<String, Object> invitation = db.sql("SELECT email, status FROM organization_invitation"
                        + " WHERE id = CAST(:id AS uuid)")
                .bind("id", invitationId).fetch().one().block();
        assertThat(invitation).isNotNull();
        assertThat(invitation.get("status")).isEqualTo("revoked");
        assertThat(invitation.get("email")).isEqualTo("deleted+" + invitationId.replace("-", "")
                + "@deleted.invalid");
    }

    private String email(String accountId) {
        return db.sql("SELECT email FROM app_users WHERE id = CAST(:id AS uuid)")
                .bind("id", accountId).map(row -> row.get("email", String.class)).one().block();
    }
}
