package com.grassland.trust.dispute;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.trust.TrustItSupport;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 争议冷却期端到端测试（GL-P2-TRUST-001，T5 恢复）。
 *
 * <p>冷却期规则：争议终局后需等待默认 7 天（可配置）才能再次开争议，防止恶意重复开争议。
 * 测试环境用较短冷却期（1 秒）以加快测试。
 *
 * <p><b>注意</b>：本测试启用冷却期（dispute-cooldown-seconds=1），与 TrustItSupport 默认（禁用）不同——
 * 靠本类专属 {@code @DynamicPropertySource} 覆盖基类。冷却期"禁用"路径（hours=0/seconds=0 → 不拦）
 * 是 TrustItSupport 默认，由其余争议 IT 隐式覆盖（它们自由开争议不被冷却拦截）。
 */
class DisputeCooldownIT extends TrustItSupport {

    /**
     * 冷却期测试专用配置：启用 1 秒冷却期。
     *
     * <p>用秒级覆盖（T5 新增 {@code disputeCooldownSeconds} 字段）——历史版本错把 {@code dispute-cooldown-hours=0}
     * （语义=禁用）当作"启用 1 秒"，且 {@code dispute-cooldown-seconds} 当时不是 record 字段，故整个类被 {@code @Disabled}。
     */
    @DynamicPropertySource
    static void cooldownProps(DynamicPropertyRegistry r) {
        // 秒级覆盖优先于 dispute-cooldown-hours：1 秒冷却期（默认 168 小时 = 7 天）
        r.add("trust.adjudication.dispute-cooldown-seconds", () -> "1");
    }

    @Test
    @DisplayName("终局后冷却期内禁止开争议")
    void cooldownBlocksReopeningWithinWindow() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String eng = UUID.randomUUID().toString();

        // 开第一轮争议并终局
        String firstId = open(merchant, org, eng);
        decide(merchant, org, firstId);

        // 立即尝试开第二轮争议 → 409 冷却期错误（TrustErrorHandler 信封用 $.error）
        client().post().uri("/api/trust/disputes")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", eng, "reason", "新理由"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.error").value(msg -> assertThat(msg).asString()
                        .contains("近期已有终局争议").contains("冷却期"));
    }

    @Test
    @DisplayName("冷却期外可正常开争议")
    void cooldownAllowsReopeningAfterWindow() throws InterruptedException {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String eng = UUID.randomUUID().toString();

        // 开第一轮争议并终局
        String firstId = open(merchant, org, eng);
        decide(merchant, org, firstId);

        // 等待冷却期结束（1 秒）
        Thread.sleep(1500);

        // 冷却期后可正常开争议
        client().post().uri("/api/trust/disputes")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", eng, "reason", "冷却期后的新争议"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.data.status").isEqualTo("open")
                .jsonPath("$.data.engagementRef").isEqualTo(eng);

        // 验证有两条终局争议记录
        long count = db.sql("SELECT COUNT(*)::int FROM dispute_case"
                        + " WHERE engagement_ref = :ref AND status = 'final'")
                .bind("ref", eng).map(r -> r.get("count", Long.class)).one().block();
        assertThat(count).isEqualTo(1);  // 第一条终局
    }

    @Test
    @DisplayName("冷却期检查基于终局时间（decided_at）")
    void cooldownBasedOnDecidedAt() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String eng = UUID.randomUUID().toString();

        // 开争议
        String id = open(merchant, org, eng);

        // 直接更新 decided_at 为 2 秒前（模拟终局已过冷却期）
        Instant twoSecondsAgo = Instant.now().minus(2, ChronoUnit.SECONDS);
        db.sql("UPDATE dispute_case SET status = 'final', decided_at = :when, version = version + 1"
                        + " WHERE id = CAST(:id AS uuid)")
                .bind("when", twoSecondsAgo)
                .bind("id", id)
                .fetch().rowsUpdated().block();

        // 冷却期已过，可开新争议
        client().post().uri("/api/trust/disputes")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", eng, "reason", "冷却期已过"))
                .exchange()
                .expectStatus().isCreated();
    }

    // ---------- helpers ----------

    private String open(String merchant, String org, String eng) {
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = client().post().uri("/api/trust/disputes")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", eng))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        return (String) ((Map<String, Object>) resp.get("data")).get("id");
    }

    private void decide(String merchant, String org, String id) {
        client().post().uri("/api/trust/disputes/" + id + "/decide")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("decision", "in_merchant_favor"))
                .exchange()
                .expectStatus().isOk();
    }
}
