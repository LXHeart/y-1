package com.grassland.identity.recommenderprofile;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 推荐官画像端到端（PRD 六）。覆盖：自维护 PUT/GET、整份覆盖语义、他人可读、
 * 没填过 → 空画像而非 404、标签清洗与超量 400、未登录 401。
 */
class RecommenderProfileControllerIT extends IdentityItSupport {

    private static final String FULL_BODY = """
            {"displayName":"小草","bio":"美食探店三年",
             "contentTags":["美食探店"," 生活日常 ","美食探店"],
             "domainTags":["餐饮"],
             "socialAccounts":[{"platform":"xiaohongshu","handle":"@grass","followers":12000}]}
            """;

    @Test
    void updateThenReadOwnProfile() {
        var me = seedAccount("rp-owner@example.com");
        openRecommender(me.cookie());

        client().put().uri("/api/me/recommender-profile")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + me.cookie())
                .bodyValue(FULL_BODY)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.displayName").isEqualTo("小草")
                // 标签清洗：去空白 + 去重（"美食探店" 只留一个）
                .jsonPath("$.data.contentTags.length()").isEqualTo(2)
                .jsonPath("$.data.contentTags[1]").isEqualTo("生活日常")
                // 社交账号出到 API 是**真数组对象**，不是 JSON 字符串
                .jsonPath("$.data.socialAccounts[0].platform").isEqualTo("xiaohongshu")
                .jsonPath("$.data.socialAccounts[0].followers").isEqualTo(12000);

        client().get().uri("/api/me/recommender-profile")
                .header("Cookie", "y1.sid=" + me.cookie()).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.data.bio").isEqualTo("美食探店三年")
                .jsonPath("$.data.domainTags[0]").isEqualTo("餐饮");
    }

    /** PUT 是整份覆盖：第二次不带社交账号就该清空，而不是保留上一次的。 */
    @Test
    void putReplacesWholeProfile() {
        var me = seedAccount("rp-replace@example.com");
        openRecommender(me.cookie());
        putProfile(me.cookie(), FULL_BODY);

        client().put().uri("/api/me/recommender-profile")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + me.cookie())
                .bodyValue("{\"displayName\":\"改名了\",\"contentTags\":[],\"domainTags\":[],\"socialAccounts\":[]}")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.displayName").isEqualTo("改名了")
                .jsonPath("$.data.bio").doesNotExist()
                .jsonPath("$.data.contentTags.length()").isEqualTo(0)
                .jsonPath("$.data.socialAccounts.length()").isEqualTo(0);
    }

    /** 商家审核报名时要看得到对方画像——这正是本功能存在的理由（此前只看得到一串 UUID）。 */
    @Test
    void merchantCanReadAnotherAccountProfile() {
        var recommender = seedAccount("rp-target@example.com");
        var merchant = seedAccount("rp-viewer@example.com");
        openRecommender(recommender.cookie());
        putProfile(recommender.cookie(), FULL_BODY);

        client().get().uri("/api/recommenders/" + recommender.accountId() + "/profile")
                .header("Cookie", "y1.sid=" + merchant.cookie()).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.data.displayName").isEqualTo("小草")
                .jsonPath("$.data.accountId").isEqualTo(recommender.accountId())
                // 画像端点只回画像，不回账号信息
                .jsonPath("$.data.email").doesNotExist();
    }

    /** 没填过资料的人：返回空画像而不是 404——「这人没填」本身就是商家需要的事实。 */
    @Test
    void unfilledProfileReturnsEmptyNotFound() {
        var recommender = seedAccount("rp-empty@example.com");
        var merchant = seedAccount("rp-empty-viewer@example.com");

        client().get().uri("/api/recommenders/" + recommender.accountId() + "/profile")
                .header("Cookie", "y1.sid=" + merchant.cookie()).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.data.displayName").doesNotExist()
                .jsonPath("$.data.contentTags.length()").isEqualTo(0);
    }

    @Test
    void rejectsProfileUpdateWhenRecommenderIdentityIsNotOpened() {
        var me = seedAccount("rp-unopened@example.com");

        client().put().uri("/api/me/recommender-profile")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + me.cookie())
                .bodyValue(FULL_BODY)
                .exchange().expectStatus().isEqualTo(409).expectBody()
                .jsonPath("$.error").isEqualTo("未开通推荐官身份，请先开通");

        Long profileCount = db.sql("SELECT COUNT(*)::int AS c FROM recommender_profile"
                        + " WHERE account_id = CAST(:acct AS uuid)")
                .bind("acct", me.accountId())
                .map(row -> row.get("c", Integer.class)).one().block().longValue();
        assertThat(profileCount).isZero();
    }

    @Test
    void rejectsTooManyTags() {
        var me = seedAccount("rp-toomany@example.com");
        openRecommender(me.cookie());
        String tags = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(i -> "\"标签" + i + "\"").reduce((a, b) -> a + "," + b).orElseThrow();

        client().put().uri("/api/me/recommender-profile")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + me.cookie())
                .bodyValue("{\"contentTags\":[" + tags + "]}")
                .exchange().expectStatus().is4xxClientError();
    }

    @Test
    void rejectsAnonymous() {
        client().get().uri("/api/me/recommender-profile").exchange().expectStatus().isUnauthorized();
    }

    private void openRecommender(String cookie) {
        client().post().uri("/api/me/identities")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"type\":\"recommender\"}")
                .exchange().expectStatus().isCreated();
    }

    private void putProfile(String cookie, String body) {
        client().put().uri("/api/me/recommender-profile")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue(body).exchange().expectStatus().isOk();
    }
}
