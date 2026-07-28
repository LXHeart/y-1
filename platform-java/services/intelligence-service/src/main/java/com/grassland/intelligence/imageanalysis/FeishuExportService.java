package com.grassland.intelligence.imageanalysis;

import com.grassland.intelligence.imageanalysis.FeishuClient.FeishuUploadImage;
import com.grassland.intelligence.imageanalysis.FeishuCredentialsRepository.FeishuCredentials;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 飞书文档导出编排（草场 intelligence Slice 6）。逐字移植 legacy {@code feishu-export.service.ts} 的 {@code exportToFeishu}：
 * 生成参数段 → 上传图片段（占位块→传媒体→替换；权限错整单失败，其余跳过并写「[图片上传失败]」）→ 评价内容段（批量 50）。
 * 返回 {@code {documentId, documentUrl}}（documentUrl 指向 bytedance.feishu.cn）。
 */
@Component
public class FeishuExportService {

    static final int BATCH_SIZE = 50;
    static final String DEFAULT_DOC_TITLE = "图片评价导出";
    static final String FILENAME_PREFIX = "image-export";
    private static final Pattern LATIN1_SUPPLEMENT = Pattern.compile("[À-ÿ]");
    private static final Pattern CJK = Pattern.compile("[㐀-鿿]");

    private final FeishuClient feishu;

    public FeishuExportService(FeishuClient feishu) {
        this.feishu = feishu;
    }

    public Mono<FeishuExportResult> export(FeishuExportInput input) {
        FeishuCredentials creds = input.credentials();
        if (!creds.configured()) {
            return Mono.error(new IntelligenceException(400, "飞书应用凭证未配置，请在设置中填写 App ID 和 App Secret"));
        }
        return feishu.tenantAccessToken(creds.appId(), creds.appSecret())
                .flatMap(token -> buildDocument(token, input));
    }

    private Mono<FeishuExportResult> buildDocument(String token, FeishuExportInput input) {
        String title = input.title() == null || input.title().isBlank() ? DEFAULT_DOC_TITLE : input.title();
        return feishu.createDocument(token, title, input.credentials().folderToken())
                .flatMap(documentId -> writeSections(token, documentId, input)
                        .thenReturn(new FeishuExportResult(documentId, FeishuClient.DOCUMENT_URL_PREFIX + documentId)));
    }

    private Mono<Void> writeSections(String token, String documentId, FeishuExportInput input) {
        return appendMetadata(token, documentId, input)
                .then(appendImages(token, documentId, input))
                .then(appendReview(token, documentId, input));
    }

    private Mono<Void> appendMetadata(String token, String documentId, FeishuExportInput input) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        if (input.platform() != null) {
            lines.add("平台: " + ("dianping".equals(input.platform()) ? "大众点评" : "淘宝"));
        }
        if (input.reviewLength() != null && input.reviewLength() != 0) {
            lines.add("字数: " + input.reviewLength());
        }
        if (input.feelings() != null && !input.feelings().isBlank()) {
            lines.add("补充感受: " + input.feelings());
        }
        if (input.runId() != null && !input.runId().isBlank()) {
            lines.add("追踪 ID: " + input.runId());
        }
        if (lines.isEmpty()) {
            return Mono.empty();
        }
        blocks.add(textBlock("生成参数", 3));
        for (String line : lines) {
            blocks.add(textBlock(line, null));
        }
        return feishu.appendBlocks(token, documentId, blocks).then();
    }

    private Mono<Void> appendImages(String token, String documentId, FeishuExportInput input) {
        List<FeishuImageInput> images = input.images();
        if (images == null || images.isEmpty()) {
            return Mono.empty();
        }
        return feishu.appendBlocks(token, documentId, List.of(textBlock("上传图片", 3)))
                .then(Flux.fromIterable(images)
                        .concatMap(image -> exportOneImage(token, documentId, image))
                        .then());
    }

    /** 单图失败只追加占位并继续后续图片；上传权限缺失（99991672）仍整单失败。 */
    private Mono<Void> exportOneImage(String token, String documentId, FeishuImageInput image) {
        return feishu.appendBlocks(token, documentId, List.of(imageBlock()))
                .flatMap(blockId -> feishu.uploadMedia(token, toUploadImage(image), blockId)
                        .flatMap(fileToken -> feishu.replaceImageBlock(token, documentId, blockId, fileToken)))
                .onErrorResume(error -> {
                    if (isPermissionError(error)) {
                        return Mono.error(error);
                    }
                    return feishu.appendBlocks(token, documentId, List.of(textBlock("[图片上传失败]", null))).then();
                });
    }

    private Mono<Void> appendReview(String token, String documentId, FeishuExportInput input) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(textBlock("评价内容", 3));
        if (input.title() != null && !input.title().isBlank()) {
            blocks.add(textBlock(input.title(), 4));
        }
        for (String line : input.review().split("\n")) {
            if (!line.trim().isEmpty()) {
                blocks.add(textBlock(line, null));
            }
        }
        if (input.tags() != null && !input.tags().isEmpty()) {
            blocks.add(textBlock("标签: " + String.join("、", input.tags()), null));
        }
        List<List<Map<String, Object>>> batches = partition(blocks, BATCH_SIZE);
        return Flux.fromIterable(batches).concatMap(batch -> feishu.appendBlocks(token, documentId, batch)).then();
    }

    private static boolean isPermissionError(Throwable error) {
        return error instanceof IntelligenceException ie
                && FeishuClient.IMAGE_UPLOAD_PERMISSION_MESSAGE.equals(ie.getMessage());
    }

    private static FeishuUploadImage toUploadImage(FeishuImageInput image) {
        return new FeishuUploadImage(image.bytes(), image.mimeType(), buildFileName(image));
    }

    private static String buildFileName(FeishuImageInput image) {
        return FILENAME_PREFIX + "-" + System.currentTimeMillis() + "." + fileExtension(image);
    }

    private static String fileExtension(FeishuImageInput image) {
        if ("image/png".equals(image.mimeType())) return "png";
        if ("image/webp".equals(image.mimeType())) return "webp";
        if ("image/jpeg".equals(image.mimeType())) return "jpg";
        String name = decodeMojibake(image.originalName()).trim();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase();
            return "jpeg".equals(ext) ? "jpg" : ext;
        }
        return "jpg";
    }

    /** 镜像 legacy {@code decodePossiblyMojibakeFileName}：含 Latin-1 补充且无 CJK 时按 latin1→utf8 修复。 */
    private static String decodeMojibake(String name) {
        if (name == null || name.isEmpty()) return name;
        if (CJK.matcher(name).find() || !LATIN1_SUPPLEMENT.matcher(name).find()) return name;
        return new String(name.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> textBlock(String content, Integer heading) {
        Map<String, Object> element = Map.of("text_run", Map.of("content", content, "text_element_style", Map.of()));
        List<Map<String, Object>> elements = List.of(element);
        if (heading != null && heading == 3) return Map.of("block_type", 5, "heading3", Map.of("elements", elements));
        if (heading != null && heading == 4) return Map.of("block_type", 6, "heading4", Map.of("elements", elements));
        return Map.of("block_type", 2, "text", Map.of("elements", elements));
    }

    private static Map<String, Object> imageBlock() {
        return Map.of("block_type", 27, "image", Map.of());
    }

    private static <T> List<List<T>> partition(List<T> source, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < source.size(); i += size) {
            out.add(List.copyOf(source.subList(i, Math.min(i + size, source.size()))));
        }
        return out;
    }

    /** 飞书导出输入。{@code credentials} 来自用户设置（controller 注入）；images 为 multipart 解析后的字节。 */
    public record FeishuExportInput(
            FeishuCredentials credentials, String review, String title, List<String> tags,
            List<FeishuImageInput> images, String platform, Integer reviewLength, String feelings, String runId) {}

    public record FeishuImageInput(byte[] bytes, String mimeType, String originalName) {}

    public record FeishuExportResult(String documentId, String documentUrl) {}
}
