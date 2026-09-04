package com.grassland.identity.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.identity.admin.AdminUserController.AdjustCreditsRequest;
import com.grassland.identity.admin.AdminUserRepository.AdminUserRow;
import com.grassland.identity.admin.FinanceCreditsAdminClient.AccountBalance;
import com.grassland.identity.assertion.BackendRole;
import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.identityprofile.IdentityAuditLogRepository;
import com.grassland.identity.organization.CurrentAccountResolver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;

/**
 * {@link AdminUserController} 纯逻辑单测：余额合并、award/refund 分支、入参校验。
 * 依赖全 Mockito stub（不依赖 R2DBC / testcontainers）。
 * 鉴权（requireAdmin → 403/401）与端到端由 {@code AdminUserControllerIT} 覆盖。
 */
class AdminUserControllerTest {

    private CurrentAccountResolver accounts;
    private AdminUserRepository adminUsers;
    private FinanceCreditsAdminClient financeCredits;
    private BackendRoleRepository backendRoles;
    private IdentityAuditLogRepository identityAudits;
    private AdminUserController controller;

    @BeforeEach
    void setUp() {
        accounts = mock(CurrentAccountResolver.class);
        adminUsers = mock(AdminUserRepository.class);
        financeCredits = mock(FinanceCreditsAdminClient.class);
        backendRoles = mock(BackendRoleRepository.class);
        identityAudits = mock(IdentityAuditLogRepository.class);
        // requireAdmin/requireRole 默认放行（admin 鉴权由 IT 覆盖；listUsers 走 requireRole 三角色）
        when(accounts.requireAdmin(any())).thenReturn(Mono.just(stubAdmin()));
        when(accounts.requireRole(any(), any(BackendRole[].class))).thenReturn(Mono.just(stubAdmin()));
        // 分页信封的 COUNT 默认回 0（行查与 COUNT 同口径，由 IT 覆盖真实值）
        when(adminUsers.countAll(any(), any(), any())).thenReturn(Mono.just(0L));
        // backendRoles 默认返回空集（角色授予/撤销由 IT 覆盖）
        when(backendRoles.findByAccountId(anyString())).thenReturn(Mono.just(java.util.Set.of()));
        when(identityAudits.findByAccount(anyString())).thenReturn(reactor.core.publisher.Flux.empty());
        // finance 默认成功
        when(financeCredits.fetchBalances(any())).thenReturn(Mono.just(Map.of()));
        when(financeCredits.award(anyString(), anyInt(), anyString())).thenReturn(Mono.empty());
        when(financeCredits.refund(anyString(), anyInt(), anyString())).thenReturn(Mono.empty());
        controller = new AdminUserController(accounts, adminUsers, financeCredits, backendRoles, identityAudits);
    }

    @Test
    void listUsersMergesBalancesAndReturnsEnvelope() {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        when(adminUsers.findAll(null, null, null, 50, 0)).thenReturn(Mono.just(List.of(
                row(a, "a@example.com", false, null),
                row(b, "b@example.com", false, null))));
        when(adminUsers.countAll(null, null, null)).thenReturn(Mono.just(2L));
        when(financeCredits.fetchBalances(eq(List.of(a, b)))).thenReturn(Mono.just(Map.of(
                a, new AccountBalance(5, 5, 0))));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = controller.listUsers(request()).block().getBody();

        assertThat(body).isNotNull();
        assertThat(body.get("success")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users = (List<Map<String, Object>>) data.get("items");
        assertThat(users).hasSize(2);
        // 分页信封回显钗制后的 total/limit/offset
        assertThat(data.get("total")).isEqualTo(2L);
        assertThat(data.get("limit")).isEqualTo(50);
        assertThat(data.get("offset")).isEqualTo(0);
        // a 有余额 5
        Map<String, Object> userA = users.stream().filter(u -> u.get("email").equals("a@example.com")).findFirst().orElseThrow();
        assertThat(userA.get("balance")).isEqualTo(5);
        // identities 聚合随行透传（#72 卡 A）
        @SuppressWarnings("unchecked")
        Map<String, Object> identitiesA = (Map<String, Object>) userA.get("identities");
        assertThat(identitiesA.get("recommender")).isEqualTo(false);
        assertThat(identitiesA.get("ownedOrgNames")).isNull();
        // b 无账户 → 余额 0
        Map<String, Object> userB = users.stream().filter(u -> u.get("email").equals("b@example.com")).findFirst().orElseThrow();
        assertThat(userB.get("balance")).isEqualTo(0);
        assertThat(userB.get("totalEarned")).isEqualTo(0);
    }

    @Test
    void listUsersEscapesWildcardCharactersBeforeRepositorySearch() {
        when(adminUsers.findAll("%100\\%\\_ok%", null, null, 50, 0)).thenReturn(Mono.just(List.of()));

        controller.listUsers(" 100%_ok ", null, null, null, null, request()).block();

        verify(adminUsers).findAll("%100\\%\\_ok%", null, null, 50, 0);
        verify(adminUsers).countAll("%100\\%\\_ok%", null, null);
    }

    @Test
    void listUsersRejectsOverlongSearch() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> controller.listUsers("x".repeat(101), null, null, null, null, request()).block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
    }

    @Test
    void listUsersRejectsUnknownIdentityType() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> controller.listUsers(null, null, "influencer", null, null, request()).block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identityType");
    }

    @Test
    void listUsersPassesFiltersToRepository() {
        when(adminUsers.findAll(any(), any(), any(), anyInt(), anyInt())).thenReturn(Mono.just(List.of()));

        controller.listUsers(null, "suspended", "merchant", null, null, request()).block();

        verify(adminUsers).findAll(null, "suspended", "merchant", 50, 0);
        verify(adminUsers).countAll(null, "suspended", "merchant");
    }

    @Test
    void adjustCreditsPositiveAmountCallsAward() {
        String acct = UUID.randomUUID().toString();
        controller.adjustCredits(new AdjustCreditsRequest(acct, 10, "赠送"), request()).block();

        verify(financeCredits).award(acct, 10, "赠送");
        verify(financeCredits, never()).refund(anyString(), anyInt(), anyString());
    }

    @Test
    void adjustCreditsNegativeAmountCallsRefundWithAbsoluteValue() {
        String acct = UUID.randomUUID().toString();
        controller.adjustCredits(new AdjustCreditsRequest(acct, -3, "扣减"), request()).block();

        verify(financeCredits).refund(acct, 3, "扣减");
        verify(financeCredits, never()).award(anyString(), anyInt(), anyString());
    }

    @Test
    void adjustCreditsRejectsBlankNote() {
        String acct = UUID.randomUUID().toString();
        // 直接调方法 → 抛 IllegalArgumentException（@ExceptionHandler 转 400 由 Spring 层处理，IT 覆盖）
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> controller.adjustCredits(new AdjustCreditsRequest(acct, 1, "  "), request()).block())
                .isInstanceOf(IllegalArgumentException.class);
        verify(financeCredits, never()).award(anyString(), anyInt(), anyString());
    }

    @Test
    void adjustCreditsRejectsInvalidUserId() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> controller.adjustCredits(new AdjustCreditsRequest("not-a-uuid", 1, "note"), request()).block())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listUsersPropagatesFinanceUpstreamError() {
        when(adminUsers.findAll(null, null, null, 50, 0)).thenReturn(Mono.just(List.of(
                row(UUID.randomUUID().toString(), "x@example.com", false, null))));
        when(financeCredits.fetchBalances(any())).thenReturn(Mono.error(new IdentityException(502, "积分服务暂不可用")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.listUsers(request()).block())
                .isInstanceOfSatisfying(IdentityException.class,
                        e -> assertThat(e.status()).isEqualTo(502));
    }

    private static com.grassland.identity.user.AuthUser stubAdmin() {
        return new com.grassland.identity.user.AuthUser(
                UUID.randomUUID().toString(), "admin@example.com", "admin", "admin", "active");
    }

    private static AdminUserRow row(String id, String email, boolean hasRecommender, String ownedOrgNames) {
        return new AdminUserRow(id, email, null, "user", "active", Instant.parse("2026-01-01T00:00:00Z"),
                hasRecommender, false, false, ownedOrgNames, List.of());
    }

    private static ServerHttpRequest request() {
        return mock(ServerHttpRequest.class);
    }
}
