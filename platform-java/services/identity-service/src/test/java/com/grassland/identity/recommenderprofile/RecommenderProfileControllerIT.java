package com.grassland.identity.recommenderprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.grassland.identity.IdentityItSupport;
import com.grassland.identity.auth.IdentityException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

/**
 * 推荐官画像端到端（PRD 六；任务书 #29+#30 #29 扩资料字段 + 头像）。覆盖：自维护 PUT/GET、
 * 整份覆盖语义、他人可读、没填过 → 空画像而非 404、标签清洗与超量 400、未登录 401、
 * 新字段往返、作品样本 URL 校验、头像复验与 presigned URL 白名单。
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

    /** 任务书 #29+#30 #29 新增资料字段：城市/地区（trim+去重）/内容偏好/作品样本 往返。 */
    @Test
    void newProfileFieldsRoundTrip() {
        var me = seedAccount("rp-fields@example.com");
        openRecommender(me.cookie());
        String body = """
                {"displayName":"字段齐全",
                 "residentCity":" 上海 ",
                 "serviceRegions":["上海"," 杭州 ","上海"],
                 "contentPreferences":"美食探店、城市漫步",
                 "workSamples":[
                    {"platform":"xiaohongshu","title":"探店合集","url":"https://xhslink.com/abc"},
                    {"platform":"douyin","url":"https://v.douyin.com/xyz"}]}
                """;
        client().put().uri("/api/me/recommender-profile")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + me.cookie())
                .bodyValue(body)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.residentCity").isEqualTo("上海")
                // service_regions 归一化镜像 tags：trim + 去重
                .jsonPath("$.data.serviceRegions.length()").isEqualTo(2)
                .jsonPath("$.data.serviceRegions[1]").isEqualTo("杭州")
                .jsonPath("$.data.contentPreferences").isEqualTo("美食探店、城市漫步")
                .jsonPath("$.data.workSamples.length()").isEqualTo(2)
                .jsonPath("$.data.workSamples[0].url").isEqualTo("https://xhslink.com/abc")
                .jsonPath("$.data.workSamples[1].title").doesNotExist();
    }

    /** 作品样本 url 必须 http(s)：javascript:/data: 等 scheme 直接 400。 */
    @Test
    void rejectsNonHttpWorkSampleUrl() {
        var me = seedAccount("rp-badurl@example.com");
        openRecommender(me.cookie());
        client().put().uri("/api/me/recommender-profile")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + me.cookie())
                .bodyValue("{\"workSamples\":[{\"platform\":\"x\",\"url\":\"javascript:alert(1)\"}]}")
                .exchange().expectStatus().is4xxClientError();
    }

    /** 头像闭环：PUT 复验通过 → 落库；self 读回 avatarMediaId+avatarUrl；公开读只回 avatarUrl 不外泄 media id。 */
    @Test
    void avatarRoundTripAndPublicWhitelist() {
        var me = seedAccount("rp-avatar@example.com");
        var viewer = seedAccount("rp-avatar-viewer@example.com");
        openRecommender(me.cookie());
        String mediaId = UUID.randomUUID().toString();
        String body = "{\"displayName\":\"有头像\",\"avatarMediaId\":\"" + mediaId + "\"}";

        client().put().uri("/api/me/recommender-profile")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + me.cookie())
                .bodyValue(body)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.avatarMediaId").isEqualTo(mediaId)
                .jsonPath("$.data.avatarUrl").isEqualTo("https://cdn.example.com/avatar/" + mediaId);

        // self GET：avatarMediaId 供表单回填 + avatarUrl 展示
        client().get().uri("/api/me/recommender-profile")
                .header("Cookie", "y1.sid=" + me.cookie()).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.data.avatarMediaId").isEqualTo(mediaId)
                .jsonPath("$.data.avatarUrl").isEqualTo("https://cdn.example.com/avatar/" + mediaId);

        // 公开 GET：只回 avatarUrl，media id 不外泄（D6/D7）
        client().get().uri("/api/recommenders/" + me.accountId() + "/profile")
                .header("Cookie", "y1.sid=" + viewer.cookie()).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.data.avatarUrl").isEqualTo("https://cdn.example.com/avatar/" + mediaId)
                .jsonPath("$.data.avatarMediaId").doesNotExist();
    }

    /** 头像复验失败（错 owner/未传完）→ 400，且不落库。 */
    @Test
    void avatarReverifyFailureRejects() {
        var me = seedAccount("rp-avatar-bad@example.com");
        openRecommender(me.cookie());
        String mediaId = UUID.randomUUID().toString();
        when(avatarMediaClient.requireUsable(eq(UUID.fromString(mediaId)), any()))
                .thenReturn(Mono.error(new IdentityException(400, "头像媒体与当前账号不匹配或尚未完成上传")));

        client().put().uri("/api/me/recommender-profile")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + me.cookie())
                .bodyValue("{\"avatarMediaId\":\"" + mediaId + "\"}")
                .exchange().expectStatus().isBadRequest();

        Long avatarCount = db.sql("SELECT COUNT(*)::int AS c FROM recommender_profile"
                        + " WHERE account_id = CAST(:acct AS uuid) AND avatar_media_id IS NOT NULL")
                .bind("acct", me.accountId())
                .map(row -> row.get("c", Integer.class)).one().block().longValue();
        assertThat(avatarCount).isZero();
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
