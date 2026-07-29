package com.grassland.intelligence.articleimage;

import com.grassland.intelligence.articleimage.ArticleImageService.GenerateCommand;
import com.grassland.intelligence.articleimage.ArticleImageService.RecommendCommand;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 文章图片四端点（intelligence Slice 5），保持 legacy URL 和响应形状。 */
@RestController
@RequestMapping("/api/article-generation")
public class ArticleImageController {

    private static final int MAX_FILES = 4;
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_FIELD_BYTES = 32 * 1024;
    private static final int MAX_FIELDS = 8;
    private static final int MAX_PARTS = MAX_FILES + MAX_FIELDS;
    private static final Set<MediaType> IMAGE_TYPES = Set.of(
            MediaType.IMAGE_JPEG, MediaType.IMAGE_PNG, MediaType.parseMediaType("image/webp"));
    private static final Set<String> SIZES = Set.of("1024x1024", "1024x1792", "1792x1024");

    private final IntelligenceCallerResolver callers;
    private final ArticleImageService images;

    public ArticleImageController(IntelligenceCallerResolver callers, ArticleImageService images) {
        this.callers = callers;
        this.images = images;
    }

    @PostMapping(value = "/image-recommendations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> recommend(
            @RequestBody RecommendationRequest body, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> images.recommend(new RecommendCommand(
                        body.content(), body.outline(), body.platform())))
                .map(ArticleImageController::success);
    }

    @PostMapping(value = "/search-images", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> search(
            @RequestBody SearchRequest body, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> images.search(body.keywords(), body.count()))
                .map(result -> success(Map.of("images", result)));
    }

    @PostMapping(value = "/generate-image", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> generateJson(
            @RequestBody GenerateJsonRequest body, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> images.generate(new GenerateCommand(
                        body.prompt(), body.size(), List.of())))
                .map(ArticleImageController::success);
    }

    @PostMapping(value = "/generate-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> generateMultipart(ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .then(exchange.getMultipartData())
                .flatMap(this::parseMultipart)
                .flatMap(images::generate)
                .map(ArticleImageController::success);
    }

    @GetMapping("/generated-images/{id}")
    public Mono<ResponseEntity<Resource>> generated(@PathVariable String id) {
        if (!isCanonicalUuid(id)) {
            return Mono.error(new IntelligenceException(404, "图片不存在"));
        }
        return images.findGenerated(id)
                .map(stored -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(30)).cachePrivate())
                        .body((Resource) new ByteArrayResource(stored.bytes())))
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "图片不存在或已过期")));
    }

    private Mono<GenerateCommand> parseMultipart(MultiValueMap<String, Part> parts) {
        Mono<GenerateCommand> parsed = Mono.defer(() -> parseMultipartValidated(parts));
        return parsed.doFinally(signal -> Flux.fromIterable(parts.values())
                .flatMapIterable(value -> value)
                .flatMap(Part::delete)
                .onErrorComplete()
                .subscribe());
    }

    private Mono<GenerateCommand> parseMultipartValidated(MultiValueMap<String, Part> parts) {
        int partCount = parts.values().stream().mapToInt(List::size).sum();
        if (partCount > MAX_PARTS) {
            return Mono.error(new IllegalArgumentException("图片上传失败，请检查文件后重试"));
        }
        for (String name : parts.keySet()) {
            if (!Set.of("prompt", "size", "images").contains(name)) {
                return Mono.error(new IllegalArgumentException("仅支持上传 images 字段的图片文件"));
            }
        }
        List<Part> promptParts = parts.getOrDefault("prompt", List.of());
        List<Part> sizeParts = parts.getOrDefault("size", List.of());
        List<Part> imageParts = parts.getOrDefault("images", List.of());
        return parseMultipartParts(parts, promptParts, sizeParts, imageParts);
    }

    private Mono<GenerateCommand> parseMultipartParts(
            MultiValueMap<String, Part> parts,
            List<Part> promptParts,
            List<Part> sizeParts,
            List<Part> imageParts) {
        long fieldCount = parts.values().stream().flatMap(List::stream)
                .filter(FormFieldPart.class::isInstance).count();
        if (fieldCount > MAX_FIELDS || promptParts.size() != 1 || sizeParts.size() > 1) {
            return Mono.error(new IllegalArgumentException("图片上传失败，请检查文件后重试"));
        }
        if (!(promptParts.getFirst() instanceof FormFieldPart promptPart)
                || (!sizeParts.isEmpty() && !(sizeParts.getFirst() instanceof FormFieldPart))) {
            return Mono.error(new IllegalArgumentException("图片上传失败，请检查文件后重试"));
        }
        if (utf8Length(promptPart.value()) > MAX_FIELD_BYTES
                || (!sizeParts.isEmpty() && utf8Length(((FormFieldPart) sizeParts.getFirst()).value()) > MAX_FIELD_BYTES)) {
            return Mono.error(new IllegalArgumentException("图片上传失败，请检查文件后重试"));
        }
        if (imageParts.size() > MAX_FILES) {
            return Mono.error(new IllegalArgumentException("最多上传 6 张图片"));
        }
        String prompt = validatePrompt(promptPart.value());
        String size = validateSize(sizeParts.isEmpty() ? null : ((FormFieldPart) sizeParts.getFirst()).value());
        return Flux.fromIterable(imageParts)
                .concatMap(this::readImage)
                .collectList()
                .map(references -> new GenerateCommand(prompt, size, references));
    }

    private Mono<ReferenceImage> readImage(Part part) {
        if (!(part instanceof FilePart file)) {
            return Mono.error(new IllegalArgumentException("仅支持上传 images 字段的图片文件"));
        }
        MediaType mediaType = file.headers().getContentType();
        if (mediaType == null || !IMAGE_TYPES.contains(mediaType)) {
            return Mono.error(new IllegalArgumentException("仅支持 JPG、PNG、WebP 图片"));
        }
        return DataBufferUtils.join(file.content(), MAX_FILE_BYTES + 1)
                .map(buffer -> {
                    try {
                        if (buffer.readableByteCount() > MAX_FILE_BYTES) {
                            throw new IllegalArgumentException("单张图片不能超过 5 MB");
                        }
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return new ReferenceImage(mediaType.toString(), bytes);
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                });
    }

    private static Map<String, Object> success(Object data) {
        return Map.of("success", true, "data", data);
    }

    private static String validatePrompt(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > 4_000) {
            throw new IllegalArgumentException("请输入生图提示词");
        }
        return value;
    }

    private static String validateSize(String raw) {
        String value = raw == null || raw.isBlank() ? "1024x1024" : raw.trim();
        if (!SIZES.contains(value)) {
            throw new IllegalArgumentException("图片尺寸无效");
        }
        return value;
    }

    private static int utf8Length(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private static boolean isCanonicalUuid(String id) {
        return id != null && id.matches(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    public record RecommendationRequest(String content, String outline, String platform) {
        public RecommendationRequest {
            content = content == null ? "" : content.trim();
            outline = optional(outline);
            platform = platform == null || platform.isBlank() ? "wechat" : platform.trim();
            if (content.length() < 10) {
                throw new IllegalArgumentException("正文内容过短");
            }
            if (content.length() > 20_000) {
                throw new IllegalArgumentException("正文内容过长");
            }
            if (outline != null && outline.length() > 10_000) {
                throw new IllegalArgumentException("文章大纲过长");
            }
            if (!Set.of("wechat", "zhihu", "xiaohongshu").contains(platform)) {
                throw new IllegalArgumentException("文章平台无效");
            }
        }
    }

    public record SearchRequest(String keywords, Integer count) {
        public SearchRequest {
            keywords = keywords == null ? "" : keywords.trim();
            count = count == null ? 3 : count;
            if (keywords.isEmpty() || keywords.length() > 200) {
                throw new IllegalArgumentException("请输入搜图关键词");
            }
            if (count < 1 || count > 10) {
                throw new IllegalArgumentException("搜图数量需为 1-10");
            }
        }
    }

    public record GenerateJsonRequest(String prompt, String size) {
        public GenerateJsonRequest {
            prompt = validatePrompt(prompt);
            size = validateSize(size);
        }
    }

    private static String optional(String value) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }
}
