package com.grassland.intelligence.contentlibrary;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 素材推荐打分器（PRD §4.8「按任务和平台智能推荐素材」的真推荐算法，替换 Slice 14 的 category 筛选占位）。
 *
 * <p>确定性多信号加权排序，不依赖外部 AI provider——同样输入必出同样排序，可单测、可审计、可解释
 * （每条结果附 reasons）。信号与权重（总分约 0-90）：
 * <ul>
 *   <li>关键词命中（≤40）：标题包含 +6/词、标签全等 +5/词、标签部分包含 +2/词。词来自任务权威上下文
 *       （标题+描述+要求文本，{@link #tokenize} 中文按 ≤4 字整词/长串滑窗二元组、拉丁词小写化）或显式 keywords。</li>
 *   <li>分类精确匹配（+15）：请求/任务指定 category 与素材分类一致。</li>
 *   <li>内容形式适配（≤12）：video 形式偏爱视频素材，图文类形式（graphic/article/moments/image-text）偏爱图片。</li>
 *   <li>平台偏好（+4）：视频型平台（抖音/快手/B站/视频号）视频素材加分，图文型平台图片素材加分
 *       （平台 id 与 contracts/platform-format-rules.json 对齐）。</li>
 *   <li>库权重（≤10）：任务模式下商家授权素材优先（PRD「复用商家素材/授权素材」）——授权商家 10、
 *       本组织 8、个人 5、公共 3。</li>
 *   <li>时效（≤10）：创建时间 180 天线性衰减。</li>
 * </ul>
 *
 * <p>候选池只含调用者本就可访问的素材（个人/被授权/本组织/公共），推荐只重排不越权——授权由
 * {@code ContentAssetRecommendationService} 的候选查询保证。排序：分数降序 → 创建时间降序 → id 降序（确定性）。
 */
public final class ContentAssetRecommender {

    /** 平台 id 与 contracts/platform-format-rules.json 对齐；未知平台无平台偏好分。 */
    private static final Set<String> VIDEO_PLATFORMS = Set.of("douyin", "kuaishou", "bilibili", "wechat-channels");
    private static final Set<String> IMAGE_PLATFORMS =
            Set.of("xiaohongshu", "dianping", "wechat-official", "zhihu", "moments");
    private static final Set<String> IMAGE_FORMS = Set.of("graphic", "article", "moments", "image-text");
    private static final Set<String> VIDEO_FORMS = Set.of("video", "video-text");
    private static final int MAX_TERMS = 24;
    private static final int KEYWORD_CAP = 40;
    private static final int RECENCY_WINDOW_DAYS = 180;

    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("[\\u4e00-\\u9fff]+|[A-Za-z0-9]+");

    /** 候选来源桶（决定库权重与 reason 文案）。 */
    public enum Bucket {
        GRANTED_MERCHANT(10, "商家授权素材"),
        OWN_ORG(8, "本组织素材"),
        PERSONAL(5, "个人素材"),
        PUBLIC(3, "公共素材");

        final int weight;
        final String label;

        Bucket(int weight, String label) {
            this.weight = weight;
            this.label = label;
        }
    }

    /** 打分输入：平台/内容形式/分类/检索词 + 打分基准时间（测试可注入固定时钟）。 */
    public record Query(String platform, String contentForm, AssetCategory category,
                        List<String> terms, Instant now) {
        public Query {
            terms = terms == null ? List.of() : List.copyOf(terms);
            now = now == null ? Instant.now() : now;
        }
    }

    /** 打分结果：素材 + 来源桶 + 总分 + 解释（稳定文案，顺序固定）。 */
    public record Scored(ContentAsset asset, Bucket bucket, int score, List<String> reasons) {}

    /** 中文按 ≤4 字整词、更长串滑窗二元组；拉丁/数字词小写化（≥2 字符）。去重保序，上限 {@value MAX_TERMS}。 */
    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find() && terms.size() < MAX_TERMS) {
            String token = matcher.group();
            if (token.codePoints().anyMatch(cp -> cp >= 0x4e00 && cp <= 0x9fff)) {
                if (token.length() <= 4) {
                    terms.add(token);
                } else {
                    for (int i = 0; i + 2 <= token.length() && terms.size() < MAX_TERMS; i++) {
                        terms.add(token.substring(i, i + 2));
                    }
                }
            } else if (token.length() >= 2) {
                terms.add(token.toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(terms);
    }

    public static Scored score(ContentAsset asset, Bucket bucket, Query query) {
        List<String> reasons = new ArrayList<>();
        int score = 0;

        score += keywordScore(asset, query, reasons);
        if (query.category() != null && query.category() == asset.category()) {
            score += 15;
            reasons.add("分类匹配");
        }
        int fit = mediaFitScore(asset, query, reasons);
        score += fit;
        score += bucket.weight;
        reasons.add(bucket.label);
        score += recencyScore(asset, query.now(), reasons);

        return new Scored(asset, bucket, score, List.copyOf(reasons));
    }

    /** 关键词命中：标题包含 +6、标签全等 +5、标签互含 +2；封顶 {@value KEYWORD_CAP}。reason 每词至多一条。 */
    private static int keywordScore(ContentAsset asset, Query query, List<String> reasons) {
        if (query.terms().isEmpty()) {
            return 0;
        }
        String title = asset.title() == null ? "" : asset.title();
        int total = 0;
        int noted = 0;
        for (String term : query.terms()) {
            int gained = 0;
            String hitTag = null;
            if (title.contains(term)) {
                gained += 6;
            }
            for (String tag : asset.tags()) {
                if (tag.equalsIgnoreCase(term)) {
                    gained += 5;
                    hitTag = tag;
                    break;
                }
                if (hitTag == null && (tag.contains(term) || term.contains(tag)) && tag.length() >= 2) {
                    gained += 2;
                    hitTag = tag;
                }
            }
            total = Math.min(KEYWORD_CAP, total + gained);
            if (gained > 0 && noted < 3) {
                reasons.add((hitTag != null && !title.contains(term)
                        ? "标签命中「" + hitTag + "」" : "标题命中「" + term + "」"));
                noted++;
            }
        }
        return total;
    }

    /** 内容形式适配 + 平台偏好：video/video-text → 视频 +12（图片 +3）；图文形式 → 图片 +12（视频 +2）。 */
    private static int mediaFitScore(ContentAsset asset, Query query, List<String> reasons) {
        String mime = asset.mimeType() == null ? "" : asset.mimeType().toLowerCase(Locale.ROOT);
        boolean isVideo = mime.startsWith("video/");
        boolean isImage = mime.startsWith("image/");
        if (!isVideo && !isImage) {
            return 0;
        }
        boolean videoForm = query.contentForm() != null && VIDEO_FORMS.contains(query.contentForm());
        boolean imageForm = query.contentForm() != null && IMAGE_FORMS.contains(query.contentForm());
        int fit = 0;
        if (videoForm) {
            fit += isVideo ? 12 : isImage ? 3 : 0;
        } else if (imageForm) {
            fit += isImage ? 12 : isVideo ? 2 : 0;
        }
        String platform = query.platform();
        if (platform != null && (VIDEO_PLATFORMS.contains(platform) && isVideo
                || IMAGE_PLATFORMS.contains(platform) && isImage)) {
            fit += 4;
        }
        if (fit > 0 && (videoForm || imageForm)) {
            reasons.add("内容形式适配");
        }
        return fit;
    }

    /** 时效：180 天线性衰减（创建即 10 分 → 180 天外 0 分）；30 天内追加 reason。 */
    private static int recencyScore(ContentAsset asset, Instant now, List<String> reasons) {
        if (asset.createdAt() == null) {
            return 0;
        }
        long days = Duration.between(asset.createdAt(), now).toDays();
        if (days < 0) {
            days = 0;
        }
        if (days >= RECENCY_WINDOW_DAYS) {
            return 0;
        }
        int score = (int) Math.round(10d * (RECENCY_WINDOW_DAYS - days) / RECENCY_WINDOW_DAYS);
        if (days <= 30) {
            reasons.add("近期更新");
        }
        return score;
    }
}
