package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

/**
 * 任务书 #64 卡7：BGM 曲库治理台 CRUD（admin-only、魔数 sniff、被引用仅停用）与选曲映射。
 */
@DisplayName("BGM library admin")
class BgmTrackAdminIT extends IntelligenceItSupport {

    private static final String ADMIN = "71717171-7171-7171-7171-717171717171";
    private static final String USER = "72727272-7272-7272-7272-727272727272";

    @MockitoBean
    ObjectStorageAdapter storage;

    @Autowired
    BgmTrackRepository tracks;

    @Autowired
    BgmSelectionService selection;

    @BeforeEach
    void clean() {
        reset(storage);
        Mockito.doAnswer(invocation -> null).when(storage)
                .putObject(anyString(), any(byte[].class), anyString());
        when(storage.presignDownload(anyString(), any(Long.class)))
                .thenAnswer(invocation -> java.net.URI.create("https://media.example.test/bgm-preview"));

        db.sql("DELETE FROM video_shot_take").then()
                .then(db.sql("DELETE FROM video_shot_audio").then())
                .then(db.sql("DELETE FROM video_production_task").then())
                .then(db.sql("DELETE FROM video_shot").then())
                .then(db.sql("DELETE FROM video_storyboard").then())
                .then(db.sql("DELETE FROM bgm_track").then())
                .block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("非管理员 403；管理员可上传（mp3 ID3 魔数）并在列表看到")
    void adminOnlyUploadAndList() {
        client().get().uri("/api/admin/bgm-tracks")
                .header("X-Grassland-Identity", sign(USER, "recommender"))
                .exchange().expectStatus().isForbidden();

        client().post().uri("/api/admin/bgm-tracks")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(uploadBody("清晨轻快", List.of("轻快", "电子")).build()))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.name").isEqualTo("清晨轻快")
                .jsonPath("$.data.enabled").isEqualTo(true)
                .jsonPath("$.data.contentType").isEqualTo("audio/mpeg")
                .jsonPath("$.data.moodTags.length()").isEqualTo(2);

        client().get().uri("/api/admin/bgm-tracks")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.total").isEqualTo(1)
                .jsonPath("$.data.items[0].name").isEqualTo("清晨轻快");
    }

    @Test
    @DisplayName("魔数不符（文本文件）与非法情绪标签 → 400")
    void rejectsNonAudioAndIllegalMood() {
        MultipartBodyBuilder textBody = new MultipartBodyBuilder();
        textBody.part("file", resource("这不是音频文件".getBytes(), "fake.mp3"));
        textBody.part("name", "假音频");
        textBody.part("moods", "轻快");
        client().post().uri("/api/admin/bgm-tracks")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(textBody.build()))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("仅支持 mp3 / m4a 音频文件");

        client().post().uri("/api/admin/bgm-tracks")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(uploadBody("坏标签", List.of("重金属")).build()))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("非法情绪标签：重金属");
    }

    @Test
    @DisplayName("启停编辑 + 未被引用可删；被成片引用时删除降级为停用")
    void toggleAndReferencedDeleteDowngradesToDisable() {
        UUID trackId = seedTrack("温暖晚风", "[\"温暖\",\"治愈\"]", true);

        client().put().uri("/api/admin/bgm-tracks/{id}", trackId)
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("enabled", false))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.enabled").isEqualTo(false);

        // 未引用 → 真删
        client().delete().uri("/api/admin/bgm-tracks/{id}", trackId)
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.deleted").isEqualTo(true);
        assertThat(tracks.findById(trackId).block(Duration.ofSeconds(5))).isNull();

        // 重新种一首并让成片任务引用 → 删除仅停用
        UUID referencedId = seedTrack("被引用曲", "[\"燃\"]", true);
        UUID storyboardId = UUID.fromString(db.sql("""
                        INSERT INTO video_storyboard(account_id, target_duration_seconds, request_payload)
                        VALUES ('it-account', 15, '{}'::jsonb) RETURNING id::text
                        """).map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
        db.sql("""
                INSERT INTO video_production_task(storyboard_id, account_id, operation_id, mode,
                    target_duration_seconds, pricing_version, unit_price_cents, estimated_cost_cents,
                    bgm_track_id)
                VALUES (CAST(:sb AS uuid), 'it-account', 'bgm-ref-op', 'video', 15, 'v1', 1, 15,
                    CAST(:track AS uuid))
                """).bind("sb", storyboardId.toString()).bind("track", referencedId.toString())
                .then().block(Duration.ofSeconds(5));

        client().delete().uri("/api/admin/bgm-tracks/{id}", referencedId)
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.deleted").isEqualTo(false)
                .jsonPath("$.data.disabled").isEqualTo(true)
                .jsonPath("$.data.referencedBy").isEqualTo(1);
        BgmTrack after = tracks.findById(referencedId).block(Duration.ofSeconds(5));
        assertThat(after.enabled()).isFalse();
    }

    @Test
    @DisplayName("选曲映射：label 含式匹配、无匹配/空库回落、禁用曲不入选")
    void selectionMoodMappingAndFallback() {
        assertThat(BgmSelectionService.tagsFor("轻快")).containsExactly("轻快", "电子");
        assertThat(BgmSelectionService.tagsFor("温暖治愈的夜晚")).containsExactly("温暖", "治愈");
        assertThat(BgmSelectionService.tagsFor("未知情绪")).isEmpty();

        // 空库 → null（合成跳过 BGM）
        assertThat(selection.pick("轻快").block(Duration.ofSeconds(5))).isNull();

        UUID warm = seedTrack("温暖曲", "[\"温暖\"]", true);
        UUID disabled = seedTrack("禁用温暖曲", "[\"温暖\"]", false);

        BgmTrack picked = selection.pick("温暖一点").block(Duration.ofSeconds(5));
        assertThat(picked.id()).isEqualTo(warm);
        // 无匹配标签 → 任意启用曲回落
        BgmTrack fallback = selection.pick("完全未知").block(Duration.ofSeconds(5));
        assertThat(fallback.id()).isEqualTo(warm);
        assertThat(fallback.id()).isNotEqualTo(disabled);
    }

    // ---------------- helpers ----------------

    private static org.springframework.core.io.Resource resource(byte[] bytes, String filename) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private static MultipartBodyBuilder uploadBody(String name, List<String> moods) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        // ID3v2 头魔数（mp3）
        byte[] mp3 = new byte[] { 'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0 };
        builder.part("file", resource(mp3, name + ".mp3"));
        builder.part("name", name);
        moods.forEach(mood -> builder.part("moods", mood));
        return builder;
    }

    private UUID seedTrack(String name, String moodsJson, boolean enabled) {
        return UUID.fromString(db.sql("""
                        INSERT INTO bgm_track(name, mood_tags, object_key, content_type, size_bytes, enabled)
                        VALUES (:name, CAST(:moods AS jsonb), :key, 'audio/mpeg', 1000, :enabled)
                        RETURNING id::text
                        """)
                .bind("name", name).bind("moods", moodsJson)
                .bind("key", "bgm/" + UUID.randomUUID())
                .bind("enabled", enabled)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }
}
