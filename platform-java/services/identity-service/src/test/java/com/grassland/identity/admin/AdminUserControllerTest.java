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
        // requireAdmin 默认放行（admin 鉴权由 IT 覆盖）
        when(accounts.requireAdmin(any())).thenReturn(Mono.just(stubAdmin()));
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
        when(adminUsers.findAll()).thenReturn(Mono.just(List.of(
                row(a, "a@example.com"),
                row(b, "b@example.com"))));
        when(financeCredits.fetchBalances(eq(List.of(a, b)))).thenReturn(Mono.just(Map.of(
                a, new AccountBalance(5, 5, 0))));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = controller.listUsers(request()).block().getBody();

        assertThat(body).isNotNull();
        assertThat(body.get("success")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users = (List<Map<String, Object>>) ((Map<String, Object>) body.get("data")).get("users");
        assertThat(users).hasSize(2);
        // a 有余额 5
        Map<String, Object> userA = users.stream().filter(u -> u.get("email").equals("a@example.com")).findFirst().orElseThrow();
        assertThat(userA.get("balance")).isEqualTo(5);
        // b 无账户 → 余额 0
        Map<String, Object> userB = users.stream().filter(u -> u.get("email").equals("b@example.com")).findFirst().orElseThrow();
        assertThat(userB.get("balance")).isEqualTo(0);
        assertThat(userB.get("totalEarned")).isEqualTo(0);
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
        when(adminUsers.findAll()).thenReturn(Mono.just(List.of(row(UUID.randomUUID().toString(), "x@example.com"))));
        when(financeCredits.fetchBalances(any())).thenReturn(Mono.error(new IdentityException(502, "积分服务暂不可用")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.listUsers(request()).block())
                .isInstanceOfSatisfying(IdentityException.class,
                        e -> assertThat(e.status()).isEqualTo(502));
    }

    private static com.grassland.identity.user.AuthUser stubAdmin() {
        return new com.grassland.identity.user.AuthUser(
                UUID.randomUUID().toString(), "admin@example.com", "admin", "admin", "active");
    }

    private static AdminUserRow row(String id, String email) {
        return new AdminUserRow(id, email, null, "user", "active", Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static ServerHttpRequest request() {
        return mock(ServerHttpRequest.class);
    }
}
