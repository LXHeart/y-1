package com.grassland.intelligence.videoproduction;

/**
 * 合成的纯函数计算（任务书 #64 卡8，§4.7）——单测锚点。
 */
final class CompositionMath {

    private CompositionMath() {}

    /** 音视频对齐规划（§4.7）：音频超视频 >8% → atempo 压音频（≤1.3）再对齐；其余视频 tpad/trim。 */
    record Alignment(double targetSeconds, Double atempo, double padSeconds) {}

    static Alignment planAlignment(double videoSeconds, Double audioSeconds, int plannedSeconds) {
        double target;
        Double atempo = null;
        if (audioSeconds == null || audioSeconds <= 0) {
            // 无配音模式：取镜头计划时长
            target = plannedSeconds;
        } else if (audioSeconds > videoSeconds && (audioSeconds - videoSeconds) / videoSeconds > 0.08) {
            atempo = Math.min(1.3, audioSeconds / videoSeconds);
            target = audioSeconds / atempo;
        } else {
            target = audioSeconds;
        }
        double pad = Math.max(0, target - videoSeconds);
        return new Alignment(roundToMillis(target), atempo, roundToMillis(pad));
    }

    /** zoompan Ken Burns 表达式（§4.7：奇数镜推近 / 偶数镜拉远）。 */
    static String zoompanExpression(int seq) {
        return seq % 2 == 1
                ? "min(zoom+0.0015,1.25)"
                : "if(lte(zoom,1.0),1.20,max(1.001,zoom-0.0015))";
    }

    /**
     * subtitles 滤镜参数（§4.7；#65 卡1 MarginV 按分辨率：竖版 60 / 横版 40）。
     * 滤镜路径转义按 ffmpeg 规则：路径中 {@code :} 与 {@code \}
     * 需转义——刻意用相对文件名 + 工作目录切到临时目录，转义面收敛为 force_style 的引号。
     */
    static String subtitleFilter(String srtFileName, String fontsDirName, int marginV) {
        return srtFileName + ":fontsdir=" + fontsDirName
                + ":force_style='FontName=Inter,FontSize=13,PrimaryColour=&H00FFFFFF,"
                + "OutlineColour=&H00000000,BorderStyle=1,Outline=2,Shadow=0,MarginV=" + marginV + "'";
    }

    private static double roundToMillis(double seconds) {
        return Math.round(seconds * 1000) / 1000.0;
    }
}
