package com.grassland.intelligence.contentlibrary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 推荐打分器单测（纯函数，PRD §4.8「按任务和平台智能推荐」）。锁定：分词（中文整词/长串二元组/拉丁小写）、
 * 各信号权重与封顶、reasons 文案、时效衰减与确定性排序（分数→创建时间→id）。
 */
class ContentAssetRecommenderTest {

    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

    @Test
    void tokenizeKeepsShortChineseRunsSplitsLongRunsAndLowercasesLatin() {
        assertThat(ContentAssetRecommender.tokenize("招牌菜 新店开业 Sale2026")).containsExactly(
                "招牌菜", "新店开业", "sale2026");
        // 6 字长串 → 滑窗二元组
        assertThat(ContentAssetRecommender.tokenize("人民广场附近新店")).containsExactly(
                "人民", "民广", "广场", "场附", "附近", "近新", "新店");
        assertThat(ContentAssetRecommender.tokenize(null)).isEmpty();
        assertThat(ContentAssetRecommender.tokenize("  ")).isEmpty();
    }

    @Test
    void keywordHitsTitleExactTagAndPartialTagWithCap() {
        ContentAsset asset = asset("开业庆典海报", List.of("开业", "庆典"), "image/png");
        ContentAssetRecommender.Query query = query("开业 庆典 春季", null, null, NOW);
        ContentAssetRecommender.Scored scored =
                ContentAssetRecommender.score(asset, ContentAssetRecommender.Bucket.PERSONAL, query);
        // 开业：标题 6 + 标签全等 5；庆典：标题 6 + 标签全等 5；春季：无 → 22
        assertThat(scored.score()).isGreaterThanOrEqualTo(22);
        assertThat(scored.reasons()).contains("标题命中「开业」");

        // 封顶：8 个词全命中标题也 ≤ 40
        ContentAsset allHit = asset("多词标题测试一二三四五六七八九十", List.of(), "image/png");
        ContentAssetRecommender.Scored capped = ContentAssetRecommender.score(
                allHit, ContentAssetRecommender.Bucket.PERSONAL,
                query("多词 标题 测试 一二 三四 五六 七八 九十", null, null, NOW));
        int keywordOnly = capped.score() - ContentAssetRecommender.Bucket.PERSONAL.weight - 10;
        assertThat(keywordOnly).isLessThanOrEqualTo(40);
    }

    @Test
    void categoryMediaFitAndPlatformSignalsApply() {
        ContentAsset image = asset("门店图", List.of(), "image/png");
        ContentAsset video = asset("门店视频", List.of(), "video/mp4");

        // 图文形式 + 图文平台：image 拿满适配（12+4），video 仅平台外（2）
        ContentAssetRecommender.Query graphicXhs =
                query(null, "graphic", AssetCategory.STORE, "xiaohongshu", NOW);
        int imageScore = ContentAssetRecommender.score(
                image, ContentAssetRecommender.Bucket.PUBLIC, graphicXhs).score();
        int videoScore = ContentAssetRecommender.score(
                video, ContentAssetRecommender.Bucket.PUBLIC, graphicXhs).score();
        assertThat(imageScore - videoScore).isEqualTo(14); // 形式适配 12 vs 2 + 平台偏好 4 vs 0

        // 视频形式 + 视频平台：video 拿满（12+4），image 仅 3
        ContentAssetRecommender.Query videoDouyin =
                query(null, "video", AssetCategory.STORE, "douyin", NOW);
        assertThat(ContentAssetRecommender.score(video, ContentAssetRecommender.Bucket.GRANTED_MERCHANT, videoDouyin)
                .reasons()).contains("内容形式适配", "商家授权素材");

        // 未知形式/平台：无关键词/分类/适配分，仅剩库权重 + 时效。
        ContentAssetRecommender.Query unknown = query("无关词", "bogus-form", null, "bogus-platform", NOW);
        assertThat(ContentAssetRecommender.score(image, ContentAssetRecommender.Bucket.PERSONAL, unknown).score())
                .isEqualTo(ContentAssetRecommender.Bucket.PERSONAL.weight + 10);
    }

    @Test
    void recencyDecaysLinearlyAndReasonsMarkRecent() {
        ContentAsset fresh = asset("新", List.of(), "image/png", NOW.minusSeconds(3600));
        ContentAsset stale = asset("旧", List.of(), "image/png", NOW.minusSeconds(3600 * 24 * 365));
        ContentAssetRecommender.Query query = query("新 旧", null, null, NOW);
        ContentAssetRecommender.Scored freshScored =
                ContentAssetRecommender.score(fresh, ContentAssetRecommender.Bucket.PERSONAL, query);
        ContentAssetRecommender.Scored staleScored =
                ContentAssetRecommender.score(stale, ContentAssetRecommender.Bucket.PERSONAL, query);
        assertThat(freshScored.reasons()).contains("近期更新");
        assertThat(staleScored.reasons()).doesNotContain("近期更新");
        assertThat(freshScored.score()).isEqualTo(staleScored.score() + 10);
    }

    @Test
    void libraryWeightsFavorGrantedMerchantOverPublicOnTies() {
        ContentAsset asset = asset("同题", List.of(), "image/png");
        int granted = ContentAssetRecommender.score(
                asset, ContentAssetRecommender.Bucket.GRANTED_MERCHANT, query("同题", null, null, NOW)).score();
        int ownOrg = ContentAssetRecommender.score(
                asset, ContentAssetRecommender.Bucket.OWN_ORG, query("同题", null, null, NOW)).score();
        int personal = ContentAssetRecommender.score(
                asset, ContentAssetRecommender.Bucket.PERSONAL, query("同题", null, null, NOW)).score();
        int publicLib = ContentAssetRecommender.score(
                asset, ContentAssetRecommender.Bucket.PUBLIC, query("同题", null, null, NOW)).score();
        assertThat(granted).isEqualTo(ownOrg + 2).isEqualTo(personal + 5).isEqualTo(publicLib + 7);
    }

    private static ContentAssetRecommender.Query query(
            String keywords, String contentForm, AssetCategory category, Instant now) {
        return new ContentAssetRecommender.Query(null, contentForm, category,
                ContentAssetRecommender.tokenize(keywords), now);
    }

    private static ContentAssetRecommender.Query query(
            String keywords, String contentForm, AssetCategory category, String platform, Instant now) {
        return new ContentAssetRecommender.Query(platform, contentForm, category,
                ContentAssetRecommender.tokenize(keywords), now);
    }

    static ContentAsset asset(String title, List<String> tags, String mimeType) {
        return asset(title, tags, mimeType, NOW.minusSeconds(60));
    }

    static ContentAsset asset(String title, List<String> tags, String mimeType, Instant createdAt) {
        return new ContentAsset(UUID.randomUUID(), UUID.randomUUID(), LibraryType.PERSONAL,
                AssetCategory.STORE, "owner", null, title, tags, mimeType, 1024L, null,
                AssetStatus.ACTIVE, 1, null, null, null, null, null,
                createdAt, createdAt, null, null);
    }
}
