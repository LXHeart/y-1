package com.grassland.intelligence.embedding;

import com.grassland.intelligence.contentlibrary.ContentAsset;
import com.grassland.intelligence.media.MediaChecksums;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 索引/查询文本规范化（任务书 #33）。素材走固定行格式（title/category/tags/source/license_scope），
 * 空字段省略；标签规范化后排序。通用规范化：Unicode trim、连续空白折叠、ASCII 小写。
 * content_hash 是规范化文本的 SHA-256，用于索引幂等与陈旧判断；不读取对象存储文件内容。
 */
public final class EmbeddingTextNormalizer {

    private EmbeddingTextNormalizer() {}

    public static NormalizedText forAsset(ContentAsset asset) {
        List<String> lines = new ArrayList<>();
        String title = normalize(asset.title());
        if (!title.isEmpty()) {
            lines.add("title: " + title);
        }
        if (asset.category() != null) {
            lines.add("category: " + asset.category().db());
        }
        List<String> tags = asset.tags() == null ? List.of() : asset.tags().stream()
                .map(EmbeddingTextNormalizer::normalize)
                .filter(tag -> !tag.isEmpty())
                .sorted(Comparator.naturalOrder())
                .toList();
        if (!tags.isEmpty()) {
            lines.add("tags: " + String.join(" ", tags));
        }
        String source = normalize(asset.source());
        if (!source.isEmpty()) {
            lines.add("source: " + source);
        }
        String licenseScope = normalize(asset.licenseScope());
        if (!licenseScope.isEmpty()) {
            lines.add("license_scope: " + licenseScope);
        }
        String text = String.join("\n", lines);
        return new NormalizedText(text, hash(text));
    }

    /** Unicode trim、连续空白折叠为单个空格、仅 ASCII 字母小写（避免 locale 副作用）。 */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String collapsed = raw.trim().replaceAll("\\s+", " ");
        return collapsed.isEmpty() ? "" : asciiLowercase(collapsed);
    }

    public static String hash(String text) {
        return MediaChecksums.sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String asciiLowercase(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            builder.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return builder.toString();
    }

    public record NormalizedText(String text, String contentHash) {}
}
