package com.grassland.intelligence.videorecreation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.ContentPart;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 统一解析视频改编 JSON/multipart 请求，并复刻 legacy 的字段与文件限制。 */
@Component
public final class VideoRecreationAdaptationParser {

    static final int MAX_FILES = 4;
    static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    static final int MAX_FIELD_BYTES = 32 * 1024;
    static final int MAX_FIELDS = 12;
    static final int MAX_PARTS = 16;
    private static final Set<String> FIELD_NAMES = Set.of(
            "platform", "proxyVideoUrl", "extractedContent", "userInstructions", "images");
    private static final Set<String> CONTENT_FIELDS = Set.of(
            "videoCaptions", "videoScript", "charactersDescription", "voiceDescription",
            "propsDescription", "sceneDescription");
    private static final Set<String> INSTRUCTION_FIELDS = Set.of(
            "scriptInstruction", "characterInstruction", "scenePropsInstruction", "voiceInstruction");

    private final ObjectMapper mapper = new ObjectMapper();
    private final String publicBackendOrigin;

    public VideoRecreationAdaptationParser(Environment environment) {
        this.publicBackendOrigin = environment.getProperty("app.public-backend-origin", "");
    }

    public VideoRecreationAdaptationRequest parseJson(Map<String, Object> body) {
        if (body == null) throw new IllegalArgumentException("请求参数无效");
        String platform = requiredString(body, "platform");
        String proxyVideoUrl = requiredString(body, "proxyVideoUrl");
        Map<String, Object> rawContent = object(body.get("extractedContent"), "缺少可改编的视频内容");
        Map<String, String> content = parseStringMap(rawContent, CONTENT_FIELDS, false);
        Map<String, String> instructions = parseOptionalObject(body.get("userInstructions"), INSTRUCTION_FIELDS);
        validate(platform, proxyVideoUrl, content, instructions);
        return new VideoRecreationAdaptationRequest(platform, proxyVideoUrl, content, instructions, List.of());
    }

    public Mono<VideoRecreationAdaptationRequest> parseMultipart(MultiValueMap<String, Part> parts) {
        return Mono.defer(() -> parseMultipartValidated(parts))
                .doFinally(signal -> Flux.fromIterable(parts.values())
                        .flatMapIterable(value -> value)
                        .flatMap(Part::delete)
                        .onErrorComplete()
                        .subscribe());
    }

    private Mono<VideoRecreationAdaptationRequest> parseMultipartValidated(MultiValueMap<String, Part> parts) {
        int partCount = parts.values().stream().mapToInt(List::size).sum();
        if (partCount > MAX_PARTS) return Mono.error(new IllegalArgumentException("图片上传失败，请检查文件后重试"));
        if (parts.keySet().stream().anyMatch(name -> !FIELD_NAMES.contains(name))) {
            return Mono.error(new IllegalArgumentException("仅支持上传 images 字段的图片文件"));
        }
        long fieldCount = parts.values().stream().flatMap(List::stream)
                .filter(FormFieldPart.class::isInstance).count();
        if (fieldCount > MAX_FIELDS) return Mono.error(new IllegalArgumentException("图片上传失败，请检查文件后重试"));

        return requiredScalar(parts, "platform")
                .zipWith(requiredScalar(parts, "proxyVideoUrl"))
                .zipWith(requiredScalar(parts, "extractedContent"))
                .zipWith(optionalScalar(parts, "userInstructions"))
                .flatMap(values -> {
                    String platform = values.getT1().getT1().getT1();
                    String proxy = values.getT1().getT1().getT2();
                    Map<String, String> content = parseStringMap(
                            parseNestedObject(values.getT1().getT2(), "缺少可改编的视频内容"), CONTENT_FIELDS, false);
                    Map<String, String> instructions = values.getT2() == null
                            ? Map.of() : parseOptionalInstructionJson(values.getT2());
                    validate(platform, proxy, content, instructions);
                    List<Part> imageParts = parts.getOrDefault("images", List.of());
                    if (imageParts.size() > MAX_FILES) return Mono.error(new IllegalArgumentException("最多上传 6 张图片"));
                    return Flux.fromIterable(imageParts).concatMap(this::readImage).collectList()
                            .map(images -> new VideoRecreationAdaptationRequest(
                                    platform, proxy, content, instructions, images));
                });
    }

    private Mono<String> requiredScalar(MultiValueMap<String, Part> parts, String name) {
        List<Part> values = parts.getOrDefault(name, List.of());
        if (values.size() != 1 || !(values.getFirst() instanceof FormFieldPart field)) {
            return Mono.error(new IllegalArgumentException("请求参数无效"));
        }
        if (utf8Length(field.value()) > MAX_FIELD_BYTES) {
            return Mono.error(new IllegalArgumentException("图片上传失败，请检查文件后重试"));
        }
        return Mono.just(field.value());
    }

    private Mono<String> optionalScalar(MultiValueMap<String, Part> parts, String name) {
        List<Part> values = parts.getOrDefault(name, List.of());
        if (values.isEmpty()) return Mono.empty();
        if (values.size() != 1 || !(values.getFirst() instanceof FormFieldPart field)) {
            return Mono.error(new IllegalArgumentException("图片上传失败，请检查文件后重试"));
        }
        if (utf8Length(field.value()) > MAX_FIELD_BYTES) {
            return Mono.error(new IllegalArgumentException("图片上传失败，请检查文件后重试"));
        }
        return Mono.just(field.value());
    }

    private Mono<ContentPart> readImage(Part part) {
        if (!(part instanceof FilePart file)) {
            return Mono.error(new IllegalArgumentException("仅支持上传 images 字段的图片文件"));
        }
        MediaType type = file.headers().getContentType();
        MediaType webp = MediaType.parseMediaType("image/webp");
        if (type == null || !(MediaType.IMAGE_JPEG.isCompatibleWith(type)
                || MediaType.IMAGE_PNG.isCompatibleWith(type) || webp.isCompatibleWith(type))) {
            return Mono.error(new IllegalArgumentException("仅支持 JPG、PNG、WebP 图片"));
        }
        return DataBufferUtils.join(file.content(), MAX_FILE_BYTES + 1).map(buffer -> {
            try {
                if (buffer.readableByteCount() > MAX_FILE_BYTES) {
                    throw new IllegalArgumentException("单张图片不能超过 5 MB");
                }
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                return ContentPart.image("data:" + type + ";base64,"
                        + java.util.Base64.getEncoder().encodeToString(bytes));
            } finally {
                DataBufferUtils.release(buffer);
            }
        });
    }

    private Map<String, Object> parseNestedObject(String raw, String message) {
        try {
            return mapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception error) {
            throw new IllegalArgumentException(message);
        }
    }

    private Map<String, String> parseOptionalInstructionJson(String raw) {
        try {
            return parseStringMap(mapper.readValue(raw, new TypeReference<Map<String, Object>>() {}), INSTRUCTION_FIELDS, true);
        } catch (Exception error) {
            return Map.of();
        }
    }

    private Map<String, String> parseOptionalObject(Object value, Set<String> allowed) {
        if (value == null) return Map.of();
        return parseStringMap(object(value, "请求参数无效"), allowed, true);
    }

    private Map<String, String> parseStringMap(Map<String, Object> raw, Set<String> allowed, boolean optional) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (!allowed.contains(entry.getKey())) continue;
            if (!(entry.getValue() instanceof String value)) throw new IllegalArgumentException("请求参数无效");
            String trimmed = value.trim();
            int max = allowed == INSTRUCTION_FIELDS ? 2_000 : 10_000;
            if (trimmed.length() > max) {
                throw new IllegalArgumentException(allowed == INSTRUCTION_FIELDS ? "请求参数无效" : "提取内容过长，请精简后重试");
            }
            if (!trimmed.isEmpty() || !optional) result.put(entry.getKey(), trimmed);
        }
        return Map.copyOf(result);
    }

    private void validate(String platform, String proxy, Map<String, String> content, Map<String, String> instructions) {
        if (!("douyin".equals(platform) || "bilibili".equals(platform))) throw new IllegalArgumentException("请求参数无效");
        if (proxy == null || proxy.trim().isEmpty()) throw new IllegalArgumentException("缺少视频代理地址");
        if (!isAllowedProxy(platform, proxy.trim())) throw new IllegalArgumentException("视频代理地址无效");
        int total = content.values().stream().filter(value -> !value.isEmpty()).mapToInt(String::length).sum();
        if (content.values().stream().noneMatch(value -> !value.isEmpty())) throw new IllegalArgumentException("缺少可改编的视频内容");
        if (total > 20_000) throw new IllegalArgumentException("提取内容过长，请精简后重试");
        instructions.values().forEach(value -> {
            if (value.length() > 2_000) throw new IllegalArgumentException("请求参数无效");
        });
    }

    private boolean isAllowedProxy(String platform, String value) {
        try {
            URI parsed = URI.create(value);
            if (parsed.isAbsolute()) {
                URI expected = publicBackendOrigin.isEmpty() ? null : URI.create(publicBackendOrigin);
                if (expected == null || !expected.getScheme().equals(parsed.getScheme())
                        || !expected.getAuthority().equals(parsed.getAuthority())) return false;
            }
            return parsed.getPath() != null && parsed.getPath().matches("^/api/" + platform + "/proxy/[^/]+$");
        } catch (Exception error) {
            return false;
        }
    }

    private static Map<String, Object> object(Object value, String message) {
        if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException(message);
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> { if (key instanceof String name) result.put(name, item); });
        return result;
    }

    private static String requiredString(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (!(value instanceof String string)) throw new IllegalArgumentException("请求参数无效");
        return string.trim();
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
