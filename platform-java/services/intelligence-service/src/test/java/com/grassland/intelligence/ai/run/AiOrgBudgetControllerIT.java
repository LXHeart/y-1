package com.grassland.intelligence.ai.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.security.IdentityOrgAuthorizationClient;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/** 组织 AI 全局预算管理入口（任务书 #37）。 */
@DisplayName("AiOrgBudgetController (组织 AI 预算管理)")
class AiOrgBudgetControllerIT extends IntelligenceItSupport {

    private static final String ORG = "org-budget-37";
    private static final String OWNER = "owner-budget-37";
    private static final String ADMIN = "admin-budget-37";
    private static final String MEMBER = "member-budget-37";

    @MockitoBean
    IdentityOrgAuthorizationClient orgAuthorization;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM ai_model_budget").then().block();
    }

    @Test
    @DisplayName("owner/admin 可读写；未设置时返回不限和暂无计量")
    void ownerAndAdminCanManageUnlimitedBudget() {
        allow(ADMIN, ORG);
        client().get().uri(path(ORG))
                .header("X-Grassland-Identity", sign(ADMIN, "merchant"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.configured").isEqualTo(false)
                .jsonPath("$.data.version").isEqualTo(0)
                .jsonPath("$.data.maxTokensPerRun").doesNotExist()
                .jsonPath("$.data.usage.measured").isEqualTo(false)
                .jsonPath("$.data.usage.dailyTokens").doesNotExist();

        allow(OWNER, ORG);
        client().put().uri(path(ORG))
                .header("X-Grassland-Identity", sign(OWNER, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"expectedVersion":0,"maxTokensPerRun":100,"maxTokensDaily":1000,
                         "maxTokensMonthly":10000,"maxCentsPerRun":10,"maxCentsDaily":100,
                         "maxCentsMonthly":1000}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.configured").isEqualTo(true)
                .jsonPath("$.data.version").isEqualTo(1)
                .jsonPath("$.data.maxTokensDaily").isEqualTo(1000)
                .jsonPath("$.data.usage.measured").isEqualTo(true)
                .jsonPath("$.data.usage.dailyTokens").isEqualTo(0);
    }

    @Test
    @DisplayName("member 与跨组织访问统一为 404")
    void unauthorizedOrganizationAccessIsHidden() {
        when(orgAuthorization.require(MEMBER, ORG, "admin"))
                .thenReturn(Mono.error(new IntelligenceException(403, "组织权限不足")));
        client().get().uri(path(ORG))
                .header("X-Grassland-Identity", sign(MEMBER, "merchant"))
                .exchange()
                .expectStatus().isNotFound();

        String otherOrg = "org-other-37";
        when(orgAuthorization.require(ADMIN, otherOrg, "admin"))
                .thenReturn(Mono.error(new IntelligenceException(404, "组织不存在")));
        client().put().uri(path(otherOrg))
                .header("X-Grassland-Identity", sign(ADMIN, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"expectedVersion\":0,\"maxTokensDaily\":100}")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("更新递增 version；旧 version 冲突；全空删除恢复不限")
    void optimisticUpdateAndDeleteRestoreUnlimited() {
        allow(ADMIN, ORG);
        put("{\"expectedVersion\":0,\"maxTokensDaily\":100,\"maxTokensMonthly\":1000}")
                .jsonPath("$.data.version").isEqualTo(1);
        put("{\"expectedVersion\":1,\"maxTokensDaily\":200,\"maxTokensMonthly\":2000}")
                .jsonPath("$.data.version").isEqualTo(2)
                .jsonPath("$.data.maxTokensDaily").isEqualTo(200);

        client().put().uri(path(ORG))
                .header("X-Grassland-Identity", sign(ADMIN, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"expectedVersion\":1,\"maxTokensDaily\":300}")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.error").isEqualTo("AI 预算已被其他管理员修改，请重新载入");

        put("{\"expectedVersion\":2}")
                .jsonPath("$.data.configured").isEqualTo(false)
                .jsonPath("$.data.version").isEqualTo(0)
                .jsonPath("$.data.usage.measured").isEqualTo(false);
        Long rows = db.sql("SELECT COUNT(*) AS n FROM ai_model_budget WHERE organization_id=:org")
                .bind("org", ORG).map((row, meta) -> row.get("n", Long.class)).one().block();
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("负数与同单位非单调上限返回 400")
    void invalidLimitsAreRejected() {
        allow(ADMIN, ORG);
        badPut("{\"expectedVersion\":0,\"maxTokensDaily\":-1}");
        badPut("{\"expectedVersion\":0,\"maxTokensPerRun\":101,\"maxTokensDaily\":100}");
        badPut("{\"expectedVersion\":0,\"maxCentsDaily\":11,\"maxCentsMonthly\":10}");
    }

    @Test
    @DisplayName("GET 仅返回真实计数，逾期日/月窗口按零展示并标记当前超限")
    void getReturnsMeasuredUsageAndAppliesWindowReset() {
        allow(ADMIN, ORG);
        db.sql("""
                INSERT INTO ai_model_budget(
                    organization_id, capability, provider,
                    max_tokens_daily, max_tokens_monthly, max_cents_daily, max_cents_monthly,
                    current_daily_tokens, current_daily_cents,
                    current_monthly_tokens, current_monthly_cents, last_reset_date, enabled)
                VALUES (:org, '*', '*', 10, 100, 20, 200, 11, 21, 90, 190, CURRENT_DATE, true)
                """).bind("org", ORG).then().block();

        client().get().uri(path(ORG))
                .header("X-Grassland-Identity", sign(ADMIN, "merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.usage.measured").isEqualTo(true)
                .jsonPath("$.data.usage.dailyTokens").isEqualTo(11)
                .jsonPath("$.data.usage.dailyCents").isEqualTo(21)
                .jsonPath("$.data.usage.monthlyTokens").isEqualTo(90)
                .jsonPath("$.data.overCurrentUsage").isEqualTo(true);

        db.sql("UPDATE ai_model_budget SET last_reset_date=:date WHERE organization_id=:org")
                .bind("date", LocalDate.now().minusMonths(1))
                .bind("org", ORG).then().block();
        client().get().uri(path(ORG))
                .header("X-Grassland-Identity", sign(ADMIN, "merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.usage.dailyTokens").isEqualTo(0)
                .jsonPath("$.data.usage.dailyCents").isEqualTo(0)
                .jsonPath("$.data.usage.monthlyTokens").isEqualTo(0)
                .jsonPath("$.data.usage.monthlyCents").isEqualTo(0)
                .jsonPath("$.data.overCurrentUsage").isEqualTo(false);
    }

    private void allow(String account, String organizationId) {
        when(orgAuthorization.require(account, organizationId, "admin")).thenReturn(Mono.empty());
    }

    private org.springframework.test.web.reactive.server.WebTestClient.BodyContentSpec put(String body) {
        return client().put().uri(path(ORG))
                .header("X-Grassland-Identity", sign(ADMIN, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isOk().expectBody();
    }

    private void badPut(String body) {
        client().put().uri(path(ORG))
                .header("X-Grassland-Identity", sign(ADMIN, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isBadRequest();
    }

    private static String path(String organizationId) {
        return "/api/ai/organizations/" + organizationId + "/budget";
    }
}
