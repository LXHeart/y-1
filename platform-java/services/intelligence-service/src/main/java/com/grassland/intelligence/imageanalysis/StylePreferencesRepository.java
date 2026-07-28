package com.grassland.intelligence.imageanalysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 图片评价风格偏好仓储（草场 intelligence Slice 6）。表 {@code intelligence_style_preferences} 由 Flyway V2 建。
 * 镜像 legacy {@code image-review-style.service.ts} 经 {@code user_settings(settings_type='image-review-style')} 的读写语义，
 * 但落在 intelligence 专属表（写入只进本服务，不写回 legacy）。{@code preferences} 为 {@code string[]}（jsonb）。
 *
 * <p>用法复刻 {@code OutboxRepository}：注入 {@link DatabaseClient}，写时 {@code CAST(:json AS jsonb)}，读时 {@code ::text}。
 */
@Component
public class StylePreferencesRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final DatabaseClient db;
    private final ObjectMapper mapper = new ObjectMapper();

    public StylePreferencesRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 读取并过滤空串（镜像 legacy {@code loadImageReviewStylePreferences}：非数组/缺行→空表）。 */
    public Mono<List<String>> load(String accountId) {
        return db.sql("SELECT preferences::text FROM intelligence_style_preferences WHERE account_id = :accountId")
                .bind("accountId", accountId)
                .map(r -> r.get("preferences", String.class))
                .one()
                .map(StylePreferencesRepository::parseFiltered)
                .defaultIfEmpty(List.of());
    }

    /** 覆盖写入（镜像 legacy {@code saveImageReviewStylePreferences}，version+1）。返回写入后的列表。 */
    public Mono<List<String>> save(String accountId, List<String> preferences) {
        String json = writeJson(preferences);
        return db.sql("""
                INSERT INTO intelligence_style_preferences (account_id, preferences, version)
                VALUES (:accountId, CAST(:preferences AS jsonb), 1)
                ON CONFLICT (account_id) DO UPDATE
                SET preferences = excluded.preferences, version = intelligence_style_preferences.version + 1, updated_at = now()
                """)
                .bind("accountId", accountId)
                .bind("preferences", json)
                .then()
                .thenReturn(preferences);
    }

    private static List<String> parseFiltered(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> raw = new ObjectMapper().readValue(json, STRING_LIST);
            List<String> filtered = new ArrayList<>();
            for (String value : raw) {
                if (value != null && !value.trim().isEmpty()) {
                    filtered.add(value);
                }
            }
            return List.copyOf(filtered);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeJson(List<String> preferences) {
        try {
            return mapper.writeValueAsString(preferences);
        } catch (Exception e) {
            return "[]";
        }
    }
}
