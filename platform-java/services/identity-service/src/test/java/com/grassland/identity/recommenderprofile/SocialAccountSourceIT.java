package com.grassland.identity.recommenderprofile;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 任务书 #98 C98-03：粉丝数据来源标注与采集时间。
 *
 * <p>TC98-011 新自报数据带采集时间（source=self_reported + collectedAt=保存时点，客户端伪造值不采信）；
 * TC98-012 存量回填幂等（V50 SQL 重复执行两次结果一致，已有值不覆盖）；TC98-013 展示兼容缺省
 * （存储缺 source/collectedAt 的旧形态读出按自报解释）；TC98-014 枚举不虚标（verified/platform_fact
 * 任何路径不得产出——客户端提交即 400）。
 */
class SocialAccountSourceIT extends IdentityItSupport {

    @Test
    void tc98_011NewSelfReportedDataCarriesCollectionTime() {
        var me = seedAccount("social-source-" + UUID.randomUUID() + "@example.com");
        openRecommender(me.cookie());

        // 客户端尝试伪造 source/collectedAt：collectedAt 不采信（保存时点覆盖）；source 非 self_reported 见 TC98-014。
        client().put().uri("/api/me/recommender-profile").contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + me.cookie())
                .bodyValue("""
                        {"displayName":"小草","contentTags":[],"domainTags":[],
                         "socialAccounts":[{"platform":"xiaohongshu","handle":"@grass","followers":12000,
                                            "source":"self_reported","collectedAt":"2020-01-01T00:00:00Z"}]}
                        """)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.socialAccounts[0].source").isEqualTo("self_reported")
                .jsonPath("$.data.socialAccounts[0].collectedAt").isNotEmpty()
                // 伪造的 2020 采集时间被服务端保存时点覆盖（解析即断言非空 + 不等于旧值）。
                .jsonPath("$.data.socialAccounts[0].collectedAt")
                .value(value -> assertThat(String.valueOf(value)).startsWith("20").doesNotStartWith("2020-01-01"));

        // 重新保存刷新采集时点（自报保存时点语义）。
        client().put().uri("/api/me/recommender-profile").contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + me.cookie())
                .bodyValue("""
                        {"displayName":"小草","contentTags":[],"domainTags":[],
                         "socialAccounts":[{"platform":"douyin","handle":"@grass2","followers":800}]}
                        """)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.socialAccounts[0].platform").isEqualTo("douyin")
                .jsonPath("$.data.socialAccounts[0].source").isEqualTo("self_reported")
                .jsonPath("$.data.socialAccounts[0].collectedAt").isNotEmpty();
    }

    @Test
    void tc98_014OnlySelfReportedSourceIsAccepted() {
        var me = seedAccount("social-verified-" + UUID.randomUUID() + "@example.com");
        openRecommender(me.cookie());
        // verified/platform_fact 仅枚举预留：任何路径（含客户端提交）不得产出 → 400。
        for (String forged : new String[] { "verified", "platform_fact" }) {
            client().put().uri("/api/me/recommender-profile").contentType(MediaType.APPLICATION_JSON)
                    .header("Cookie", "y1.sid=" + me.cookie())
                    .bodyValue("{\"displayName\":\"x\",\"contentTags\":[],\"domainTags\":[],"
                            + "\"socialAccounts\":[{\"platform\":\"xiaohongshu\",\"followers\":1,\"source\":\""
                            + forged + "\"}]}")
                    .exchange().expectStatus().isBadRequest();
        }
        // 全库无 verified/platform_fact 值（读路径 only self_reported 的直接证据）。
        Long forged = db.sql(
                "SELECT count(*) AS c FROM recommender_profile"
                        + " WHERE social_accounts::text LIKE '%\"verified\"%' OR social_accounts::text LIKE '%\"platform_fact\"%'")
                .map(row -> row.get("c", Long.class)).one().block();
        assertThat(forged).isZero();
    }

    @Test
    void tc98_012BackfillIsIdempotentAndTc98_013ReadToleratesLegacyShape() {
        // 存量旧形态行（迁移前落库形态：无 source/collectedAt）。
        String legacyAccount = UUID.randomUUID().toString();
        db.sql("INSERT INTO recommender_profile(account_id, display_name, bio, content_tags, domain_tags,"
                + " social_accounts, resident_city, service_regions, content_preferences, work_samples)"
                + " VALUES (CAST(:acct AS uuid), '存量推荐官', '', ARRAY[]::text[], ARRAY[]::text[],"
                + " CAST(:social AS jsonb), '', ARRAY[]::text[], '', '[]'::jsonb)")
                .bind("acct", legacyAccount)
                .bind("social", "[{\"platform\":\"xiaohongshu\",\"handle\":\"@legacy\",\"followers\":300}]")
                .then().block();

        // TC98-013：读路径兼容缺省——旧形态读出 source=self_reported、collectedAt=null（V50 双保险）。
        // （真实存量已被启动迁移回填；此处验证读侧对未回填形态的容忍。）
        client().get().uri("/api/recommenders/" + legacyAccount + "/profile")
                .header("Cookie", "y1.sid=" + seedAccount("reader-" + UUID.randomUUID() + "@example.com").cookie())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.socialAccounts[0].source").isEqualTo("self_reported")
                .jsonPath("$.data.socialAccounts[0].collectedAt").value(v -> assertThat(v).isNull());

        // TC98-012：V50 回填 SQL 执行两次，结果幂等一致（collectedAt=行更新时间，已有值不覆盖）。
        String v50 = migrationSql("V50__social_accounts_source.sql");
        db.sql(v50).then().block();
        String afterFirst = socialAccountsOf(legacyAccount);
        db.sql(v50).then().block();
        String afterSecond = socialAccountsOf(legacyAccount);
        assertThat(afterFirst).isEqualTo(afterSecond);
        assertThat(afterFirst).contains("\"source\": \"self_reported\"").contains("collectedAt");
        // 回填后 API 读出带采集时间。
        client().get().uri("/api/recommenders/" + legacyAccount + "/profile")
                .header("Cookie", "y1.sid=" + seedAccount("reader2-" + UUID.randomUUID() + "@example.com").cookie())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.socialAccounts[0].source").isEqualTo("self_reported")
                .jsonPath("$.data.socialAccounts[0].collectedAt").isNotEmpty();
    }

    private String socialAccountsOf(String accountId) {
        return db.sql("SELECT social_accounts::text AS s FROM recommender_profile"
                + " WHERE account_id = CAST(:acct AS uuid)").bind("acct", accountId)
                .map(row -> row.get("s", String.class)).one().block();
    }

    /** 从 classpath 读迁移原文（与生产同一份 SQL，防测试与迁移漂移）。 */
    private static String migrationSql(String fileName) {
        try (var input = SocialAccountSourceIT.class.getClassLoader()
                .getResourceAsStream("db/migration/" + fileName)) {
            if (input == null) {
                throw new IllegalStateException("migration not on classpath: " + fileName);
            }
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("cannot read migration: " + fileName, error);
        }
    }

    private void openRecommender(String cookie) {
        client().post().uri("/api/me/identities").contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + cookie).bodyValue("{\"type\":\"recommender\"}").exchange()
                .expectStatus().isCreated();
    }
}
