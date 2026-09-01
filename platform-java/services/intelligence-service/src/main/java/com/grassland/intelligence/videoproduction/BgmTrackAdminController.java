package com.grassland.intelligence.videoproduction;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.speech.AudioDurationProbe;
import com.grassland.storage.ObjectStorageAdapter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 治理台 BGM 曲库（任务书 #64 卡7，P3）：上传（mp3/m4a ≤10MB，服务端魔数 sniff）、列表、
 * 编辑（名称/情绪/启停）、删除（被成片引用仅停用）、presign 试听。种子为空，运营入库指引
 * 见卡11 交付文档。
 */
@RestController
@RequestMapping("/api/admin/bgm-tracks")
public class BgmTrackAdminController {

    private final IntelligenceCallerResolver callers;
    private final BgmTrackRepository tracks;
    private final ObjectProvider<ObjectStorageAdapter> storageProvider;
    private final AudioDurationProbe durationProbe;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private final long previewTtlSeconds;

    public BgmTrackAdminController(IntelligenceCallerResolver callers, BgmTrackRepository tracks,
            ObjectProvider<ObjectStorageAdapter> storageProvider, AudioDurationProbe durationProbe,
            @Value("${media.download-url-ttl-seconds:300}") long previewTtlSeconds) {
        this.callers = callers;
        this.tracks = tracks;
        this.storageProvider = storageProvider;
        this.durationProbe = durationProbe;
        this.previewTtlSeconds = Math.max(30L, previewTtlSeconds);
    }

    public record UpdateRequest(String name, List<String> moodTags, Boolean enabled) {}

    @GetMapping
    public Mono<Map<String, Object>> list(@RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize, ServerWebExchange exchange) {
        int safePageSize = Math.min(50, Math.max(1, pageSize));
        int safePage = Math.max(1, page);
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(caller -> tracks.count(q).flatMap(total -> tracks
                        .search(q, safePageSize, (safePage - 1) * (long) safePageSize)
                        .map(this::trackBody)
                        .collectList()
                        .map(items -> {
                            Map<String, Object> data = new LinkedHashMap<>();
                            data.put("items", items);
                            data.put("total", total);
                            data.put("page", safePage);
                            data.put("pageSize", safePageSize);
                            return envelope(data);
                        })));
    }

    /** multipart：file + name + moods（重复字段）+ enabled（可选，默认 true）。 */
    @PostMapping(consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> upload(ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(caller -> exchange.getMultipartData()
                        .flatMap(parts -> parseUpload(parts)
                                .flatMap(upload -> store(upload))
                                .doFinally(signal -> Flux.fromIterable(parts.values())
                                        .flatMapIterable(value -> value)
                                        .flatMap(Part::delete).onErrorComplete().subscribe())))
                .map(track -> envelope(trackBody(track)));
    }

    @PutMapping("/{id}")
    public Mono<Map<String, Object>> update(@PathVariable UUID id, @RequestBody UpdateRequest body,
            ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(caller -> {
                    if (body == null) {
                        throw new IntelligenceException(400, "请求体不能为空");
                    }
                    String name = body.name() == null ? null : body.name().trim();
                    if (name != null && (name.isEmpty() || name.length() > 100)) {
                        throw new IntelligenceException(400, "曲名需为 1-100 字");
                    }
                    String moods = body.moodTags() == null ? null : moodsJson(body.moodTags());
                    return tracks.updateDetails(id, name, moods, body.enabled())
                            .flatMap(updated -> updated
                                    ? tracks.findById(id).map(t -> envelope(trackBody(t)))
                                    : Mono.<Map<String, Object>>error(notFound()));
                });
    }

    @DeleteMapping("/{id}")
    public Mono<Map<String, Object>> delete(@PathVariable UUID id, ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(caller -> tracks.countTaskReferences(id).flatMap(references -> {
                    if (references > 0) {
                        // 被成片引用：仅停用（引用行保留曲目标识可追溯）
                        return tracks.setEnabled(id, false)
                                .then(tracks.findById(id))
                                .map(track -> envelope(Map.of(
                                        "deleted", false, "disabled", true,
                                        "referencedBy", references, "track", trackBody(track))));
                    }
                    return tracks.delete(id).flatMap(deleted -> deleted
                            ? Mono.just(envelope(Map.of("deleted", true)))
                            : Mono.<Map<String, Object>>error(notFound()));
                }));
    }

    @GetMapping("/{id}/preview-url")
    public Mono<Map<String, Object>> previewUrl(@PathVariable UUID id, ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(caller -> tracks.findById(id)
                        .switchIfEmpty(Mono.error(notFound()))
                        .map(track -> {
                            ObjectStorageAdapter storage = storageProvider.getIfAvailable();
                            if (storage == null) {
                                throw new IntelligenceException(503, "对象存储未启用");
                            }
                            return envelope(Map.of("previewUrl",
                                    storage.presignDownload(track.objectKey(), previewTtlSeconds).toString(),
                                    "expiresInSeconds", previewTtlSeconds));
                        }));
    }

    // ---------------- 上传解析与落库 ----------------

    private record Upload(String name, String moodsJson, String contentType, byte[] bytes) {}

    private Mono<Upload> parseUpload(MultiValueMap<String, Part> parts) {
        List<Part> fileParts = parts.getOrDefault("file", List.of());
        if (fileParts.isEmpty() || !(fileParts.getFirst() instanceof FilePart file)) {
            throw new IntelligenceException(400, "请上传音频文件（file 字段）");
        }
        String name = fieldValue(parts, "name");
        if (name == null || name.isBlank() || name.trim().length() > 100) {
            throw new IntelligenceException(400, "曲名需为 1-100 字");
        }
        List<String> moods = new ArrayList<>();
        for (Part part : parts.getOrDefault("moods", List.of())) {
            if (part instanceof FormFieldPart field && !field.value().isBlank()) {
                String normalized = BgmTrack.normalizeMood(field.value());
                if (normalized == null) {
                    throw new IntelligenceException(400, "非法情绪标签：" + field.value());
                }
                if (!moods.contains(normalized)) {
                    moods.add(normalized);
                }
            }
        }
        if (moods.isEmpty()) {
            throw new IntelligenceException(400, "至少选择一个情绪标签");
        }
        return DataBufferUtils.join(file.content())
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
                    return bytes;
                })
                .map(bytes -> new Upload(name.trim(), moodsJson(moods), sniffAudio(bytes), bytes));
    }

    private Mono<BgmTrack> store(Upload upload) {
        if (upload.bytes() == null || upload.bytes().length == 0
                || upload.bytes().length > BgmTrack.MAX_SIZE_BYTES) {
            throw new IntelligenceException(400, "音频文件需在 10MB 以内");
        }
        if (upload.contentType() == null) {
            throw new IntelligenceException(400, "仅支持 mp3 / m4a 音频文件");
        }
        ObjectStorageAdapter storage = storageProvider.getIfAvailable();
        if (storage == null) {
            throw new IntelligenceException(503, "对象存储未启用");
        }
        String objectKey = "bgm/" + UUID.randomUUID();
        Integer durationMs;
        try {
            durationMs = (int) durationProbe.probe(upload.bytes());
        } catch (RuntimeException error) {
            durationMs = null; // ffprobe 不可用不阻断入库（列表展示为空时长）
        }
        return Mono.fromRunnable(() -> storage.putObject(objectKey, upload.bytes(), upload.contentType()))
                .subscribeOn(Schedulers.boundedElastic())
                .then(tracks.create(upload.name(), upload.moodsJson(), objectKey,
                        upload.contentType(), upload.bytes().length, durationMs, null))
                .flatMap(created -> created == null
                        ? Mono.error(new IntelligenceException(500, "曲库写入失败"))
                        : Mono.just(created));
    }

    private static String sniffAudio(byte[] bytes) {
        // ID3 头或 MPEG 帧同步（0xFF Ex/Fx）→ mp3；offset 4 处 ftyp → m4a/mp4 音频
        if (bytes.length >= 3 && bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            return "audio/mpeg";
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0) {
            return "audio/mpeg";
        }
        if (bytes.length >= 8 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y'
                && bytes[7] == 'p') {
            return "audio/mp4";
        }
        return null;
    }

    private static String fieldValue(MultiValueMap<String, Part> parts, String name) {
        List<Part> fields = parts.getOrDefault(name, List.of());
        return fields.isEmpty() || !(fields.getFirst() instanceof FormFieldPart field)
                ? null
                : field.value();
    }

    /** moodTags 落库存 JSON 文本，响应侧解析回数组（前端直接渲染）。 */
    private List<String> parseMoods(String raw) {
        try {
            List<String> parsed = mapper.readValue(raw == null ? "[]" : raw,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
            return parsed == null ? List.of() : parsed;
        } catch (Exception error) {
            return List.of();
        }
    }

    private String moodsJson(List<String> moods) {
        try {
            return mapper.writeValueAsString(moods);
        } catch (Exception error) {
            throw new IllegalStateException("情绪标签序列化失败", error);
        }
    }

    private static IntelligenceException notFound() {
        return new IntelligenceException(404, "曲目不存在");
    }

    private static Map<String, Object> envelope(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return body;
    }

    private Map<String, Object> trackBody(BgmTrack track) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", track.id().toString());
        body.put("name", track.name());
        body.put("moodTags", parseMoods(track.moodTags()));
        body.put("contentType", track.contentType());
        body.put("sizeBytes", track.sizeBytes());
        body.put("durationMs", track.durationMs());
        body.put("enabled", track.enabled());
        body.put("createdAt", track.createdAt() == null ? null : track.createdAt().toString());
        return body;
    }
}
