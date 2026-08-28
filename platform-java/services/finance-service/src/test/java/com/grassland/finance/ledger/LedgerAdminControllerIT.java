package com.grassland.finance.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.finance.FinanceItSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 任务书 #53 S1.4：财务对账 journals 端点统一分页信封。
 *
 * <p>锁定：{@code data:{items,total,limit,offset}} 信封、offset 翻页取到第二页、
 * total 与 organizationId 筛选同口径、limit/offset 钳制（≤0→50、&gt;200→200、负→0）、
 * FINANCE 角色门禁。journal 是 append-only 账本，测试用显式 created_at 直插保证顺序确定。
 */
class LedgerAdminControllerIT extends FinanceItSupport {

    private static final String BASE = "/api/admin/finance/journals";

    private String seedJournal(String orgId, String suffix, String createdAt) {
        String id = UUID.randomUUID().toString();
        db.sql("""
                        INSERT INTO journal (id, journal_type, operation_id, currency, organization_id, memo, created_at)
                        VALUES (CAST(:id AS uuid), 'CAPTURE', :operationId, 'CNY', CAST(:org AS uuid), :memo,
                                CAST(:createdAt AS timestamptz))
                        """)
                .bind("id", id)
                .bind("operationId", "it-journal-" + suffix + "-" + id)
                .bind("org", orgId)
                .bind("memo", "账本分页造数 " + suffix)
                .bind("createdAt", createdAt)
                .then().block();
        return id;
    }

    @Test
    void envelopePaginatesAndTotalMatchesOrganizationFilter() {
        String orgA = UUID.randomUUID().toString();
        String orgB = UUID.randomUUID().toString();
        // created_at 显式错开：DESC 序 = newest / middle / oldest
        seedJournal(orgA, "oldest", "2026-08-20T10:00:00Z");
        seedJournal(orgA, "middle", "2026-08-20T10:01:00Z");
        seedJournal(orgA, "newest", "2026-08-20T10:02:00Z");
        seedJournal(orgB, "other-org", "2026-08-20T10:03:00Z");

        // 第一页：limit=2 → 最新两条；total 与筛选同口径（orgA 共 3，不含 orgB）
        Map<?, ?> firstPage = (Map<?, ?>) client().get().uri(BASE + "?organizationId=" + orgA + "&limit=2")
                .header("X-Grassland-Identity", signRole("ledger-admin-1", "finance"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody()
                .get("data");
        List<?> page1 = (List<?>) firstPage.get("items");
        assertThat(page1).hasSize(2);
        assertThat(((Map<?, ?>) page1.get(0)).get("memo")).isEqualTo("账本分页造数 newest");
        assertThat(((Map<?, ?>) page1.get(1)).get("memo")).isEqualTo("账本分页造数 middle");
        assertThat(((Number) firstPage.get("total")).longValue()).isEqualTo(3L);

        Map<?, ?> data = (Map<?, ?>) client().get()
                .uri(BASE + "?organizationId=" + orgA + "&limit=2&offset=2")
                .header("X-Grassland-Identity", signRole("ledger-admin-1", "finance"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody()
                .get("data");
        // 第二页只剩最早一条（翻页真正跨页，不是重复第一页）
        assertThat((List<?>) data.get("items")).hasSize(1);
        assertThat(((Map<?, ?>) ((List<?>) data.get("items")).get(0)).get("memo")).isEqualTo("账本分页造数 oldest");
        assertThat(((Number) data.get("total")).longValue()).isEqualTo(3L);
        assertThat(data.get("limit")).isEqualTo(2);
        assertThat(data.get("offset")).isEqualTo(2);

        // 换组织筛选：total 收敛到该组织的 1 条
        client().get().uri(BASE + "?organizationId=" + orgB)
                .header("X-Grassland-Identity", signRole("ledger-admin-1", "finance"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.total").isEqualTo(1)
                .jsonPath("$.data.items.length()").isEqualTo(1);
    }

    @Test
    void clampsLimitAndOffsetIntoEnvelopeContract() {
        String org = UUID.randomUUID().toString();
        seedJournal(org, "clamp", "2026-08-20T11:00:00Z");

        String finance = signRole("ledger-admin-2", "finance");
        client().get().uri(BASE + "?organizationId=" + org + "&limit=0")
                .header("X-Grassland-Identity", finance)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.limit").isEqualTo(50);
        client().get().uri(BASE + "?organizationId=" + org + "&limit=999")
                .header("X-Grassland-Identity", finance)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.limit").isEqualTo(200);
        client().get().uri(BASE + "?organizationId=" + org + "&offset=-5")
                .header("X-Grassland-Identity", finance)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.offset").isEqualTo(0);
    }

    @Test
    void requiresFinanceRole() {
        String org = UUID.randomUUID().toString();
        seedJournal(org, "gate", "2026-08-20T12:00:00Z");

        client().get().uri(BASE).exchange().expectStatus().isUnauthorized();
        // 无后台角色的普通商家断言不得看平台账本流水
        client().get().uri(BASE + "?organizationId=" + org)
                .header("X-Grassland-Identity", sign("merchant-no-role", "merchant", org, "basic_publish"))
                .exchange().expectStatus().isForbidden();
        client().get().uri(BASE + "?organizationId=" + org)
                .header("X-Grassland-Identity", signRole("ledger-admin-3", "finance"))
                .exchange().expectStatus().isOk();
    }
}
