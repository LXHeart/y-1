package com.grassland.intelligence.videostudio;

import java.util.Set;

/**
 * BGM 建议 prompt（任务书 #43 D6）。
 *
 * <p>系统段定死，Qoder 原样落码。要求 LLM 只输出 JSON，不输出其他文本。
 */
final class BgmAdvicePrompts {

    static final Set<String> PLATFORMS = Set.of(
            "douyin", "xiaohongshu", "wechat", "zhihu", "kuaishou", "weibo", "dianping");
    static final Set<String> CONTENT_FORMS = Set.of(
            "口播", "剧情", "种草", "vlog", "教程", "测评", "探店", "美食", "开箱", "图文轮播");

    static final String SYSTEM = """
            你是资深短视频音频顾问。根据平台、内容形式、主题、时长和情绪倾向，给出 BGM 情绪方向与节奏规划。只输出 JSON，不要输出任何其他文本，字段结构为 {moodDirection:{label,reason,referenceStyle}, rhythm:[{timeRange,intensity,suggestion}], syncPoints:[{atSeconds,suggestion}], cautions:[]}。rhythm 按时间轴顺序覆盖全片；syncPoints 给卡点时机建议；cautions 提示版权与平台风险。""";

    static String user(String platform, String contentForm, String topic,
                       int durationSeconds, String moodHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("平台：").append(platform).append('\n');
        sb.append("内容形式：").append(contentForm).append('\n');
        sb.append("主题：").append(topic).append('\n');
        sb.append("时长：").append(durationSeconds).append("秒\n");
        if (moodHint != null && !moodHint.isBlank()) {
            sb.append("情绪倾向：").append(moodHint).append('\n');
        }
        return sb.toString();
    }

    private BgmAdvicePrompts() {}
}
