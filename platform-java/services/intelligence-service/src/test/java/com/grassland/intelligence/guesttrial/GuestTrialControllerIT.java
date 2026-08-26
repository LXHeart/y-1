package com.grassland.intelligence.guesttrial;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 游客有限体验端到端（任务书 #36 / ADR-D14）：匿名放行（无断言）、gtid cookie、额度成功计次/到量
 * error 帧（锁定语义）、provider 失败不计次、白名单外 404、限流 429、审计行只有 IP 哈希。
 * provider 走 WireMock Qwen（非流式 completeText），限流器 mock 放行/拒绝。
 */
class GuestTrialControllerIT extends IntelligenceItSupport {

    @MockitoBean
    private GuestTrialRateLimiter rateLimiter;

    @org.springframework.beans.factory.annotation.Autowired
    private GuestTrialQuotaRepository quotaRepo;

    @BeforeEach
    void stubs() {
        when(rateLimiter.check(anyString(), anyString())).thenReturn(Mono.empty());  // 默认放行
        QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"chat-1\",\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5},\"choices\":[{\"message\":{\"content\":"
                        + "\"{\\\"titles\\\":[{\\\"title\\\":\\\"探店必看\\\",\\\"hook\\\":\\\"钩子\\\"}]}\"}}]}")));
    }

    private static void stubQwenError() {
        QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse().withStatus(500)));
    }

    @Test
    @DisplayName("匿名（无断言）可用：SSE result 帧 + gtid cookie 下发 + 成功计次")
    void anonymousTrialSucceedsWithCookieAndQuota() {
        var result = client().post().uri("/api/guest-trial/article-titles")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("topic", "citywalk 咖啡店"))
                .exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody().returnResult();
        String sse = new String(result.getResponseBody());
        assertThat(sse).contains("\"progress\"");
        assertThat(sse).contains("\"result\"");
        assertThat(sse).contains("探店必看");
        assertThat(sse).endsWith("data: [DONE]\n\n");

        // [DONE] 已发 ⇒ provider/计次/审计链已执行完毕（concatWith 顺序保证），此处直接断言
        // Set-Cookie 的 gtid（与 quota/审计行同源），不经共享库反查（并行用例会互相污染最新行）。
        String gtid = gtidFromCookie(result);
        assertThat(gtid).isNotNull();
        Integer used = awaitQuotaUsed(gtid, 1);
        assertThat(used).as("成功才计次").isEqualTo(1);
        assertThat(awaitAuditOutcome(gtid)).isEqualTo("success");
    }

    @Test
    @DisplayName("同 gtid 用满 3 次 → 第 4 次 200+SSE quota_exhausted（不调 provider）")
    void quotaExhaustedEmitsErrorFrame() {
        String gtid = UUID.randomUUID().toString();
        for (int i = 0; i < 3; i++) {
            // 必须消费响应体：SSE 流读到 [DONE] 才保证计次已落库，否则第 4 次预检仍读 0。
            byte[] consumed = client().post().uri("/api/guest-trial/content-score")
                    .cookie("gtid", gtid)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("content", "一家藏在巷子里的咖啡店"))
                    .exchange().expectStatus().isOk()
                    .expectBody().returnResult().getResponseBody();
            assertThat(new String(consumed)).contains("result");
        }
        byte[] body = client().post().uri("/api/guest-trial/content-score")
                .cookie("gtid", gtid)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("content", "一家藏在巷子里的咖啡店"))
                .exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        assertThat(new String(body)).contains("quota_exhausted");
        Integer used = awaitQuotaUsed(gtid, 3);
        assertThat(used).as("到量后不再计次").isEqualTo(3);
        assertThat(db.sql("SELECT outcome FROM guest_trial_run WHERE gtid = CAST(:g AS uuid)"
                        + " AND outcome = 'quota_exhausted'").bind("g", gtid)
                .map(r -> r.get("outcome", String.class)).one().block()).isEqualTo("quota_exhausted");
    }

    @Test
    @DisplayName("provider 失败不烧额度：error 帧 provider_error，used 不动")
    void providerFailureDoesNotConsumeQuota() {
        stubQwenError();
        byte[] body = client().post().uri("/api/guest-trial/article-titles")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("topic", "探店"))
                .exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        assertThat(new String(body)).contains("provider_error");
        String gtid = db.sql("SELECT gtid::text FROM guest_trial_run WHERE outcome='provider_error'"
                        + " ORDER BY created_at DESC LIMIT 1")
                .map(r -> r.get(0, String.class)).one().block();
        Long quotaRows = db.sql("SELECT COUNT(*)::int AS c FROM guest_trial_quota"
                        + " WHERE gtid = CAST(:g AS uuid)").bind("g", gtid)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(quotaRows).as("失败不落额度行（该 gtid）").isZero();
        assertThat(auditOutcome(gtid)).isEqualTo("provider_error");
    }

    @Test
    @DisplayName("白名单外能力 → 404；空 topic → 400")
    void unknownCapabilityAndInvalidBodyRejected() {
        client().post().uri("/api/guest-trial/video-production")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isNotFound();
        client().post().uri("/api/guest-trial/article-titles")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", " "))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("IP 限流触发 → 429（SSE 前判）+ 审计 rate_limited")
    void rateLimitedRejectedBeforeSse() {
        when(rateLimiter.check(anyString(), anyString()))
                .thenReturn(Mono.just(new GuestTrialRateLimiter.Exceeded(
                        GuestTrialRateLimiter.Layer.IP_DAILY, 30)));
        var limited = client().post().uri("/api/guest-trial/article-titles")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("topic", "探店"))
                .exchange().expectStatus().isEqualTo(429)
                .expectBody().returnResult();
        String gtid = gtidFromCookie(limited);
        assertThat(gtid).isNotNull();
        assertThat(awaitAuditOutcome(gtid)).isEqualTo("rate_limited");
    }

    @Test
    @DisplayName("额度徽标 GET：返回各能力 used/limit/remaining 并种 gtid cookie")
    void quotaEndpointReturnsPerCapability() {
        var quotaResult = client().get().uri("/api/guest-trial/quota")
                .exchange().expectStatus().isOk()
                .expectBody().returnResult();
        String json = new String(quotaResult.getResponseBody());
        assertThat(json).contains("\"article-titles\"").contains("\"content-score\"").contains("\"image-review\"");
        assertThat(json).contains("\"remaining\":3").contains("\"signupBonusCredits\":50");
        assertThat(gtidFromCookie(quotaResult)).isNotBlank();
    }

    @Test
    @DisplayName("审计行只有哈希 IP：无原始 IP/UA，无输入内容与产物（R8）")
    void auditRowsContainHashedIpOnly() {
        client().post().uri("/api/guest-trial/article-titles")
                .header("X-Forwarded-For", "203.0.113.9")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("topic", "秘密主题不应入库"))
                .exchange().expectStatus().isOk()
                .expectBody().returnResult();
        String gtid = auditGtidByHash("203.0.113.9");
        String row = db.sql("SELECT capability || '|' || ip_hash FROM guest_trial_run"
                        + " WHERE gtid = CAST(:g AS uuid)").bind("g", gtid)
                .map(r -> r.get(0, String.class)).one().block();
        assertThat(row).startsWith("article-titles|");
        assertThat(row).as("IP 只存 16 hex 截断哈希").matches("article-titles\\|[0-9a-f]{16}");
        Long rawLeaks = db.sql("SELECT COUNT(*)::int AS c FROM guest_trial_run"
                        + " WHERE ip_hash LIKE '%.%'").map(r -> r.get("c", Integer.class))
                .one().block().longValue();
        assertThat(rawLeaks).isZero();
        Long contentLeaks = db.sql("SELECT COUNT(*)::int AS c FROM guest_trial_run"
                        + " WHERE capability LIKE '%秘密主题%'").map(r -> r.get("c", Integer.class))
                .one().block().longValue();
        assertThat(contentLeaks).as("审计不存输入内容").isZero();
    }

    // ---------- helpers ----------

    /** 从响应 Set-Cookie 解析 gtid（匿名请求由本响应种 cookie）。 */
    private static String gtidFromCookie(
            org.springframework.test.web.reactive.server.EntityExchangeResult<byte[]> result) {
        String cookie = result.getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        if (cookie == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("gtid=([0-9a-f-]{36})").matcher(cookie);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 按已知 IP 哈希前缀反查该用例专属的审计 gtid（互不污染）。 */
    private String auditGtidByHash(String ip) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(ip.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String hash = java.util.HexFormat.of().formatHex(digest).substring(0, 16);
            return db.sql("SELECT gtid::text FROM guest_trial_run WHERE ip_hash = :h"
                            + " ORDER BY created_at DESC LIMIT 1")
                    .bind("h", hash)
                    .map(r -> r.get(0, String.class)).one().block();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    /** SSE 响应返回与计次/审计异步落库之间有短暂窗口——轮询等待（≤5s）。 */
    private Integer awaitQuotaUsed(String gtid, int expected) {
        long deadline = System.currentTimeMillis() + 5_000L;
        Integer used = null;
        while (System.currentTimeMillis() < deadline) {
            used = db.sql("SELECT used FROM guest_trial_quota WHERE gtid = CAST(:g AS uuid)")
                    .bind("g", gtid).map(r -> r.get("used", Integer.class)).one().block();
            if (used != null && used == expected) {
                return used;
            }
            if (used != null && used > expected) {
                return used;   // 已越过期望值（并发用例共享 gtid 不可能，防御直接返回）
            }
            sleep(120L);
        }
        return used;
    }

    private String awaitAuditOutcome(String gtid) {
        long deadline = System.currentTimeMillis() + 5_000L;
        String outcome = null;
        while (System.currentTimeMillis() < deadline) {
            outcome = auditOutcome(gtid);
            if (outcome != null) {
                return outcome;
            }
            sleep(120L);
        }
        return outcome;
    }

    private String auditOutcome(String gtid) {
        return db.sql("SELECT outcome FROM guest_trial_run WHERE gtid = CAST(:g AS uuid)"
                        + " ORDER BY created_at DESC LIMIT 1")
                .bind("g", gtid)
                .map(r -> r.get("outcome", String.class)).one().block();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
