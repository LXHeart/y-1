package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 任务书 #100 C100-11（API-10 / V74 / §6.5）：每镜制作来源回归。
 *
 * <p>
 * 覆盖：TC-026 裁剪区间校验（合法 [2000,7000) 过；负数/相等/越界/非整数/长度不符/图片 trim
 * 全 400 零写入）；TC-027 音轨规则（source 要求真实音轨）；归属/committed/editVersion CAS/
 * 缺行 generated 默认与详情附 source。素材为真实 ffmpeg 产物（10s 带音轨/无音轨、图片）。
 */
@DisplayName("Video shot media source (C100-11 / API-10)")
@TestPropertySource(properties = { "ai.video-generation.worker-enabled=false" })
class VideoShotSourceIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "66666666-6666-6666-6666-666666666666";
    private static final String ACCOUNT_B = "67676767-6767-6767-6767-676767676767";

    @Autowired
    VideoMediaProbe probe;

    @MockitoBean
    ObjectStorageAdapter storage;

    private final java.util.Map<String, byte[]> objectStore = new java.util.concurrent.ConcurrentHashMap<>();

    private String videoWithAudio;
    private String videoNoAudio;
    private String imageKey;

    @BeforeEach
    void cleanAndSeed() {
        reset(storage);
        objectStore.clear();
        Mockito.doAnswer(invocation -> {
            objectStore.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(storage).putObject(anyString(), any(byte[].class), anyString());
        when(storage.getObject(anyString()))
                .thenAnswer(invocation -> objectStore.get(invocation.getArgument(0)));
        when(storage.presignDownload(anyString(), anyLong()))
                .thenAnswer(invocation -> java.net.URI.create("https://media.example.test/signed"));

        db.sql("DELETE FROM video_shot_media_source").then()
                .then(db.sql("DELETE FROM video_shot_take").then())
                .then(db.sql("DELETE FROM video_shot_audio").then())
                .then(db.sql("DELETE FROM video_production_task").then())
                .then(db.sql("DELETE FROM video_shot").then())
                .then(db.sql("DELETE FROM video_storyboard").then())
                .then(db.sql("DELETE FROM media_reference").then())
                .block(Duration.ofSeconds(10));

        // 真实素材：10s 视频（带 440Hz 音轨 / 无音轨）与 1x1 JPEG
        videoWithAudio = seedObject(realMp4(true), "video/mp4");
        videoNoAudio = seedObject(realMp4(false), "video/mp4");
        imageKey = seedObject(tinyJpeg(), "image/jpeg");
    }

    @Test
    @DisplayName("TC-026：10s 自有视频选 [2000,7000) 用于 5 秒镜头通过；非法区间全 400 零写入")
    void trimBoundaryRules() {
        Storyboard sb = seedStoryboardWithShots(5);
        UUID mediaId = seedMediaRow(videoWithAudio, "video/mp4", 5_000_000);

        Map<String, Object> ok = saveSources(sb, mediaId, 2000L, 7000L, "source", 1L, 200);
        assertThat(((Number) ok.get("editVersion")).longValue()).isEqualTo(2L);
        long rows = count("video_shot_media_source");
        assertThat(rows).isEqualTo(1L);

        // 负数 / 相等 / 越界 / 长度不符：全 400，零新增零改写
        saveSources(sb, mediaId, -1L, 7000L, "source", 2L, 400);
        saveSources(sb, mediaId, 5000L, 5000L, "source", 2L, 400);
        saveSources(sb, mediaId, 0L, 999_999L, "source", 2L, 400);
        saveSources(sb, mediaId, 0L, 4000L, "source", 2L, 400);
        saveSources(sb, mediaId, 3000L, 10_000L, "source", 2L, 400);
        assertThat(count("video_shot_media_source")).isEqualTo(rows);
        // editVersion 不因非法输入被消耗
        assertThat(currentEditVersion(sb.storyboardId())).isEqualTo(2L);
    }

    @Test
    @DisplayName("TC-026：图片 trim 拒绝；图片 narration/mute 通过")
    void imageTrimRules() {
        Storyboard sb = seedStoryboardWithShots(5);
        UUID imageId = seedMediaRow(imageKey, "image/jpeg", 100_000);
        saveSources(sb, imageId, 0L, 5000L, "narration", 1L, 400);
        Map<String, Object> saved = saveSources(sb, imageId, null, null, "mute", 1L, 200);
        assertThat(((Number) saved.get("editVersion")).longValue()).isEqualTo(2L);
    }

    @Test
    @DisplayName("TC-027：无音轨素材 source 模式拒绝；narration/mute 允许；跨账号媒体拒绝")
    void audioTrackAndOwnershipRules() {
        Storyboard sb = seedStoryboardWithShots(5);
        UUID withAudio = seedMediaRow(videoWithAudio, "video/mp4", 5_000_000);
        UUID withoutAudio = seedMediaRow(videoNoAudio, "video/mp4", 5_000_000);

        saveSources(sb, withoutAudio, 0L, 5000L, "source", 1L, 400);
        saveSources(sb, withoutAudio, 0L, 5000L, "narration", 1L, 200);
        saveSources(sb, withAudio, 1000L, 6000L, "source", 2L, 200);

        // 跨账号：媒体归 B（独立对象键——object_key 全局唯一），A 引用 → 400
        String foreignObject = seedObject(objectStore.get(videoWithAudio), "video/mp4");
        String foreign = seedMediaRowFor(ACCOUNT_B, foreignObject, "video/mp4");
        saveSources(sb, UUID.fromString(foreign), 0L, 5000L, "narration", 3L, 400);
    }

    @Test
    @DisplayName("generated 回退清引用；committed 拒改；editVersion CAS；缺行默认 generated；详情附 source")
    void lifecycleAndDetailContract() {
        Storyboard sb = seedStoryboardWithShots(5);
        UUID mediaId = seedMediaRow(videoWithAudio, "video/mp4", 5_000_000);

        saveSources(sb, mediaId, 0L, 5000L, "narration", 1L, 200);
        // generated 回退：行变 generated（media/trim 清空）
        Map<String, Object> reverted = saveSources(sb, null, null, null, null, 2L, 200);
        assertThat(sourceRowKind(sb.shotIds.get(0))).isEqualTo("generated");
        assertThat(reverted.get("sources")).isNotNull();

        // 旧 editVersion CAS → 409
        saveSources(sb, mediaId, 0L, 5000L, "narration", 1L, 409);

        // committed 拒改
        db.sql("UPDATE video_storyboard SET status='committed' WHERE id=CAST(:id AS uuid)")
                .bind("id", sb.storyboardId().toString()).then().block(Duration.ofSeconds(5));
        saveSources(sb, mediaId, 0L, 5000L, "narration", 99L, 409);

        // 分镜详情：缺行镜头 source=generated；own-media 行带完整引用
        db.sql("UPDATE video_storyboard SET status='draft' WHERE id=CAST(:id AS uuid)")
                .bind("id", sb.storyboardId().toString()).then().block(Duration.ofSeconds(5));
        saveSources(sb, mediaId, 0L, 5000L, "source", null, 200);
        Map<String, Object> detail = storyboardDetail(sb.storyboardId());
        List<Map<String, Object>> shots = castList(detail.get("shots"));
        Map<String, Object> first = shots.get(0);
        assertThat(first.get("source")).isEqualTo(Map.of("kind", "own-media", "mediaId", mediaId.toString(),
                "trimStartMs", 0, "trimEndMs", 5000, "audioMode", "source"));
        Map<String, Object> second = shots.get(1);
        assertThat(second.get("source")).isEqualTo(Map.of("kind", "generated"));
    }

    // ---- 帮手 ----

    private record Storyboard(UUID storyboardId, List<UUID> shotIds) {
    }

    private Storyboard seedStoryboardWithShots(int plannedSeconds) {
        UUID storyboardId = UUID.fromString(db.sql("INSERT INTO video_storyboard(account_id, "
                        + "target_duration_seconds, request_payload) VALUES (:account, :total, "
                        + "CAST(:payload AS jsonb)) RETURNING id::text")
                .bind("account", ACCOUNT).bind("total", Math.max(15, plannedSeconds * 2))
                .bind("payload", "{\"images\":[\"data:image/png;base64,AAAA\"],\"shopName\":\"店\"}")
                .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5)));
        List<UUID> shotIds = new ArrayList<>();
        for (int seq = 1; seq <= 2; seq++) {
            shotIds.add(UUID.fromString(db.sql("INSERT INTO video_shot(storyboard_id, seq, visual, narration, "
                            + "planned_seconds, camera_move, anchor_image_index, prompt) VALUES "
                            + "(CAST(:sb AS uuid), :seq, 'v', 'n', :planned, '固定机位', 1, 'p') RETURNING id::text")
                    .bind("sb", storyboardId.toString()).bind("seq", seq).bind("planned", plannedSeconds)
                    .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5))));
        }
        return new Storyboard(storyboardId, shotIds);
    }

    private String seedObject(byte[] content, String contentType) {
        String key = "own-media/" + UUID.randomUUID();
        objectStore.put(key, content);
        return key;
    }

    private UUID seedMediaRow(String objectKey, String mimeType, long sizeBytes) {
        return UUID.fromString(seedMediaRowFor(ACCOUNT, objectKey, mimeType, sizeBytes));
    }

    private String seedMediaRowFor(String account, String objectKey, String mimeType) {
        return seedMediaRowFor(account, objectKey, mimeType, 5_000_000);
    }

    private String seedMediaRowFor(String account, String objectKey, String mimeType, long sizeBytes) {
        return db.sql("INSERT INTO media_reference(id, owner_account_id, purpose, object_key, mime_type, "
                        + "size_bytes, source, status) VALUES (gen_random_uuid(), :account, 'store-media', "
                        + ":objectKey, :mime, :size, 'upload', 'active') RETURNING id::text")
                .bind("account", account).bind("objectKey", objectKey).bind("mime", mimeType)
                .bind("size", sizeBytes)
                .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5));
    }

    /** ffmpeg 真实 10s MP4（lavfi testsrc2 + 440Hz 正弦 / anullsrc）；MP4 需可寻址输出走临时文件。 */
    private byte[] realMp4(boolean withAudio) {
        java.nio.file.Path temporary = null;
        try {
            temporary = java.nio.file.Files.createTempFile("own-media-it-", ".mp4");
            // 真无音轨 = 不带音频输入（anullsrc 是静音音轨，ffprobe 仍能看到流）
            java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(
                    "ffmpeg", "-y", "-v", "error",
                    "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=15:duration=10"));
            if (withAudio) {
                command.addAll(java.util.List.of("-f", "lavfi", "-i", "sine=frequency=440:duration=10",
                        "-c:a", "aac", "-shortest"));
            }
            command.addAll(java.util.List.of("-c:v", "libx264", "-preset", "ultrafast", temporary.toString()));
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("ffmpeg 测试素材生成失败（exit=" + process.exitValue() + "）");
            }
            byte[] output = java.nio.file.Files.readAllBytes(temporary);
            if (output.length == 0) {
                throw new IllegalStateException("ffmpeg 测试素材为空");
            }
            return output;
        } catch (Exception e) {
            throw new IllegalStateException("ffmpeg 不可用：" + e.getMessage(), e);
        } finally {
            if (temporary != null) {
                try {
                    java.nio.file.Files.deleteIfExists(temporary);
                } catch (Exception ignored) {
                    // best-effort
                }
            }
        }
    }

    private byte[] tinyJpeg() {
        return java.util.Base64.getDecoder().decode(
                "/9j/4AAQSkZJRgABAgAAAQABAAD/2wBDAAgEBAQEBAUFBQUFBgYGBgYGBgYGBgYGBgcHBwgICAgHBwcGBgcHCAgICAkJCQgICAgJCQoKCgwMCwsODg4RERT/xABNAAEBAAAAAAAAAAAAAAAAAAAABgEBAQEAAAAAAAAAAAAAAAAAAAYHEAEAAAAAAAAAAAAAAAAAAAAAEQEAAAAAAAAAAAAAAAAAAAAA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPwA8AD//2Q==");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> saveSources(Storyboard sb, UUID mediaId, Long trimStart, Long trimEnd,
            String audioMode, Long expectedEditVersion, int status) {
        Map<String, Object> source = new LinkedHashMap<>();
        if (mediaId == null) {
            source.put("kind", "generated");
        } else {
            source.put("kind", "own-media");
            source.put("mediaId", mediaId.toString());
            source.put("trimStartMs", trimStart);
            source.put("trimEndMs", trimEnd);
            source.put("audioMode", audioMode);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("shotId", sb.shotIds().get(0).toString());
        item.put("source", source);
        Object[] holder = new Object[1];
        Map<String, Object> requestBody = new LinkedHashMap<>();
        if (expectedEditVersion != null) {
            requestBody.put("expectedEditVersion", expectedEditVersion);
        }
        requestBody.put("sources", List.of(item));
        client().patch().uri("/api/video-production/storyboards/{id}/sources", sb.storyboardId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange().expectBody(String.class).consumeWith(result -> {
                    String raw = result.getResponseBody();
                    org.assertj.core.api.Assertions.assertThat(result.getStatus().value())
                            .as("saveSources 响应体=" + raw)
                            .isEqualTo(status);
                    try {
                        Map<?, ?> envelope = raw == null ? null
                                : new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, Map.class);
                        holder[0] = envelope == null ? null : envelope.get("data");
                    } catch (Exception e) {
                        holder[0] = raw;
                    }
                });
        return holder[0] == null ? Map.of() : (Map<String, Object>) holder[0];
    }

    private Map<String, Object> storyboardDetail(UUID storyboardId) {
        Object[] holder = new Object[1];
        client().get().uri("/api/video-production/storyboards/{id}", storyboardId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).consumeWith(result ->
                        holder[0] = ((Map<?, ?>) result.getResponseBody()).get("data"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) holder[0];
        return data;
    }

    private String sourceRowKind(UUID shotId) {
        return db.sql("SELECT source_kind FROM video_shot_media_source WHERE shot_id=CAST(:id AS uuid)")
                .bind("id", shotId.toString()).map(row -> row.get(0, String.class))
                .one().block(Duration.ofSeconds(5));
    }

    private long currentEditVersion(UUID storyboardId) {
        return db.sql("SELECT edit_version FROM video_storyboard WHERE id=CAST(:id AS uuid)")
                .bind("id", storyboardId.toString()).map(row -> row.get(0, Long.class))
                .one().block(Duration.ofSeconds(5));
    }

    private long count(String table) {
        return db.sql("SELECT COUNT(*) FROM " + table).map(row -> row.get(0, Long.class))
                .one().block(Duration.ofSeconds(5));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
