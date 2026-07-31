package com.grassland.intelligence.bilibili;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Bilibili 下载文件名构建（移植 legacy {@code server/src/lib/bilibili-filename.ts}）。
 *
 * <p>把 title/author/videoId 拼成安全的下载文件名：非法字符 / 空白 / 连续短横归一为单个 {@code -}，
 * 去首尾短横，截断至 ≤80 字符（去尾部短横），保证 {@code .mp4} 后缀。全空回退 {@code bilibili-video.mp4}。
 */
public final class BilibiliFilename {

    static final String DEFAULT_DOWNLOAD_FILENAME = "bilibili-video.mp4";
    private static final int MAX_LENGTH = 80;

    private static final Pattern INVALID_CHARS = Pattern.compile("[<>:\"/\\\\|?*\\x00-\\x1F]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern REPEATED_DASH = Pattern.compile("-+");
    private static final Pattern LEADING_OR_TRAILING_DASH = Pattern.compile("^-|-$");
    private static final Pattern TRAILING_DASH = Pattern.compile("-+$");

    private BilibiliFilename() {}

    /** title-author-videoId → 安全下载文件名（{@code .mp4}）。三者皆空回退默认名。 */
    public static String buildDownloadFilename(String title, String author, String videoId) {
        List<String> parts = new ArrayList<>(3);
        for (String part : new String[] {title, author, videoId}) {
            String sanitized = sanitizeFilenamePart(part);
            if (sanitized != null) {
                parts.add(sanitized);
            }
        }
        if (parts.isEmpty()) {
            return DEFAULT_DOWNLOAD_FILENAME;
        }
        return ensureMp4Extension(truncateFilenameBase(String.join("-", parts)));
    }

    /** 对齐 legacy {@code normalizeBilibiliDownloadFilename}：单字段清洗 + {@code .mp4}；空→null。 */
    public static String normalizeDownloadFilename(String filename) {
        String sanitized = sanitizeFilenamePart(filename);
        return sanitized == null ? null : ensureMp4Extension(sanitized);
    }

    private static String sanitizeFilenamePart(String value) {
        if (value == null) {
            return null;
        }
        String normalized = INVALID_CHARS.matcher(value).replaceAll("-");
        normalized = WHITESPACE.matcher(normalized).replaceAll("-");
        normalized = REPEATED_DASH.matcher(normalized).replaceAll("-");
        normalized = LEADING_OR_TRAILING_DASH.matcher(normalized).replaceAll("");
        normalized = normalized.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String ensureMp4Extension(String filename) {
        return filename.toLowerCase().endsWith(".mp4") ? filename : filename + ".mp4";
    }

    private static String truncateFilenameBase(String base) {
        if (base.length() <= MAX_LENGTH) {
            return base;
        }
        String trimmed = TRAILING_DASH.matcher(base.substring(0, MAX_LENGTH)).replaceAll("").trim();
        return trimmed.isEmpty() ? "bilibili-video" : trimmed;
    }
}
