package com.grassland.intelligence.creationcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Database contract for append-only and idempotent creation context snapshots. */
class CreationContextSnapshotRepositoryIT extends IntelligenceItSupport {

    @Autowired
    CreationContextSnapshotRepository repository;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM ai_credit_compensation").then().block();
        db.sql("DELETE FROM ai_run").then().block();
        db.sql("DELETE FROM creation_context_snapshot").then().block();
    }

    @Test
    void createIsIdempotentAndPreservesTheFirstSnapshot() {
        CreationContextSnapshot first = snapshot("account-a", "accepted title", "qwen-plus");
        CreationContextSnapshot created = repository.create(first).block();
        CreationContextSnapshot replay = repository.create(snapshot(
                "account-a", "later title", "different-model")).block();

        assertThat(replay.id()).isEqualTo(created.id());
        assertThat(replay.taskSnapshot()).containsEntry("title", "accepted title");
        assertThat(replay.aiConfigSnapshot()).containsEntry("model", "qwen-plus");
        Long count = db.sql("SELECT COUNT(*) AS n FROM creation_context_snapshot")
                .map(row -> row.get("n", Long.class)).one().block();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void findAndOwnershipAreAccountScoped() {
        CreationContextSnapshot created = repository.create(snapshot(
                "account-owner", "accepted title", "qwen-plus")).block();

        assertThat(repository.findById(created.id()).block()).isNotNull();
        assertThat(repository.belongsTo(created.id(), "account-owner").block()).isTrue();
        assertThat(repository.belongsTo(created.id(), "account-other").block()).isFalse();
    }

    @Test
    void databaseRejectsSnapshotUpdates() {
        CreationContextSnapshot created = repository.create(snapshot(
                "account-immutable", "accepted title", "qwen-plus")).block();

        assertThatThrownBy(() -> db.sql("UPDATE creation_context_snapshot SET task_id='changed' "
                        + "WHERE id=CAST(:id AS uuid)")
                .bind("id", created.id().toString()).then().block())
                .hasMessageContaining("creation context snapshots are immutable");
    }

    private CreationContextSnapshot snapshot(String accountId, String title, String model) {
        return new CreationContextSnapshot(
                null, accountId, "organization-1", "task-1", "application-1", 3,
                "xiaohongshu", "graphic",
                Map.of("taskId", "task-1", "applicationId", "application-1",
                        "taskVersion", 3, "title", title),
                Map.of("version", PlatformCreationRuleCatalog.VERSION,
                        "platform", "xiaohongshu", "contentForm", "graphic"),
                Map.of("items", List.of(Map.of("assetId", UUID.randomUUID().toString(),
                        "validUntil", Instant.parse("2027-01-01T00:00:00Z")))),
                Map.of("resolutionType", "PLATFORM", "provider", "qwen", "model", model,
                        "platformModelVersion", 7),
                Map.of(),
                null);
    }
}
