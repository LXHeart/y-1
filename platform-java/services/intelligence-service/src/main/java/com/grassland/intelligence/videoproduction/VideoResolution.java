package com.grassland.intelligence.videoproduction;

/**
 * 分辨率与画幅映射（任务书 #65 卡1）：{@code video_storyboard.resolution} 的值集与派生规则。
 *
 * <p>仅两档：竖版 {@code 1080x1920}（缺省）与横版 {@code 1920x1080}（B 站默认）。
 * provider 请求体的 ratio（MiniMax {@code aspect_ratio} / Seedance {@code ratio}）与
 * 合成 normalize 目标、字幕 MarginV 全部由这一列派生——单一事实源，禁各处散落硬编码。
 */
final class VideoResolution {

    static final String PORTRAIT = "1080x1920";
    static final String LANDSCAPE = "1920x1080";

    private VideoResolution() {
    }

    static boolean allowed(String value) {
        return PORTRAIT.equals(value) || LANDSCAPE.equals(value);
    }

    /** 缺省分辨率：bilibili 横版，其余（含未选平台）竖版。 */
    static String defaultFor(String targetPlatform) {
        return "bilibili".equals(targetPlatform == null ? "" : targetPlatform.trim())
                ? LANDSCAPE
                : PORTRAIT;
    }

    /** provider ratio 值（'9:16'|'16:9'）；万相由 parameters.size 间接表达（1280P/1080P）。 */
    static String aspectRatioOf(String resolution) {
        return LANDSCAPE.equals(resolution) ? "16:9" : "9:16";
    }

    /** 合成 normalize 目标宽。 */
    static int widthOf(String resolution) {
        return LANDSCAPE.equals(resolution) ? 1920 : 1080;
    }

    /** 合成 normalize 目标高。 */
    static int heightOf(String resolution) {
        return LANDSCAPE.equals(resolution) ? 1080 : 1920;
    }

    /** 字幕 force_style 的 MarginV：竖版 60 / 横版 40（§4.7 契约）。 */
    static int subtitleMarginV(String resolution) {
        return LANDSCAPE.equals(resolution) ? 40 : 60;
    }
}
