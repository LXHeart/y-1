package com.grassland.intelligence.imageanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;

/**
 * 风格偏好仓储 + V2 回填的集成测试（草场 intelligence Slice 6）。testcontainers postgres，Flyway V2 已建表 + user_settings 兜底。
 */
class StylePreferencesRepositoryIT extends IntelligenceItSupport {

    @Autowired
    private StylePreferencesRepository repo;
    @Autowired
    private DatabaseClient db;

    @Test
    void loadUnknownAccountReturnsEmpty() {
        StepVerifier.create(repo.load("unknown-" + UUID.randomUUID()))
                .expectNext(List.of())
                .verifyComplete();
    }

    @Test
    void saveAndLoadRoundTrip() {
        String account = "acct-" + UUID.randomUUID();
        StepVerifier.create(repo.save(account, List.of("偏好短句", "口语化"))
                        .then(repo.load(account)))
                .expectNext(List.of("偏好短句", "口语化"))
                .verifyComplete();
    }

    @Test
    void loadFiltersBlankAndNonStringEntries() {
        String account = "acct-blank-" + UUID.randomUUID();
        // 直接写入含空串/空白的 jsonb，load 应过滤
        db.sql("INSERT INTO intelligence_style_preferences (account_id, preferences) VALUES (:id, CAST(:json AS jsonb))")
                .bind("id", account)
                .bind("json", "[\"有效\", \"  \", \"\", null]")
                .then().block();
        StepVerifier.create(repo.load(account))
                .expectNext(List.of("有效"))
                .verifyComplete();
    }

    @Test
    void saveOverwritesAndBumpsVersion() {
        String account = "acct-ver-" + UUID.randomUUID();
        repo.save(account, List.of("a")).block();
        repo.save(account, List.of("b")).block();
        Integer version = db.sql("SELECT version FROM intelligence_style_preferences WHERE account_id = :id")
                .bind("id", account).map(r -> r.get("version", Integer.class)).one().block();
        assertThat(version).isEqualTo(2);
    }

    @Test
    void backfillFromLegacyUserSettingsPopulatesStyleTableWhenLegacyTableExists() {
        String userId = UUID.randomUUID().toString();
        // 模拟 legacy migration 已先创建权威 user_settings（V2 本身不再创建 legacy-owned 表）。
        db.sql("""
                CREATE TABLE IF NOT EXISTS user_settings (
                    id uuid PRIMARY KEY,
                    user_id uuid NOT NULL,
                    settings_type text NOT NULL,
                    settings_json jsonb NOT NULL,
                    version integer NOT NULL DEFAULT 1,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    updated_at timestamptz NOT NULL DEFAULT now(),
                    CONSTRAINT user_settings_unique_user_type UNIQUE (user_id, settings_type)
                )
                """).then().block();
        db.sql("""
                INSERT INTO user_settings (id, user_id, settings_type, settings_json)
                VALUES (:id, CAST(:uid AS uuid), 'image-review-style', CAST(:json AS jsonb))
                ON CONFLICT (user_id, settings_type) DO UPDATE SET settings_json = excluded.settings_json
                """)
                .bind("id", UUID.randomUUID())
                .bind("uid", UUID.fromString(userId))
                .bind("json", "{\"preferences\":[\"偏好 A\",\"偏好 B\"],\"updatedAt\":\"2026-01-01\"}")
                .then().block();
        // 运行与 V2 回填等价的 SQL（验证回填语句在运行时也成立）
        db.sql("""
                INSERT INTO intelligence_style_preferences (account_id, preferences, version)
                SELECT user_id::text, COALESCE(settings_json->'preferences', '[]'::jsonb), 1
                FROM user_settings WHERE settings_type = 'image-review-style' AND user_id = CAST(:uid AS uuid)
                ON CONFLICT (account_id) DO NOTHING
                """)
                .bind("uid", UUID.fromString(userId))
                .then().block();

        StepVerifier.create(repo.load(userId))
                .expectNext(List.of("偏好 A", "偏好 B"))
                .verifyComplete();
    }
}
