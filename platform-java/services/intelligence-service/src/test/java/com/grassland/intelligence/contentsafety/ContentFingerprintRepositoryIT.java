package com.grassland.intelligence.contentsafety;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.contentsafety.ContentFingerprintRepository.Fingerprint;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ContentFingerprintRepositoryIT extends IntelligenceItSupport {

    @Autowired
    private ContentFingerprintRepository repository;

    @BeforeEach
    void cleanFingerprints() {
        db.sql("DELETE FROM content_fingerprint").then().block();
    }

    @Test
    void candidatesIncludeSameTaskAcrossAccountsAndOnlyRecentOwnerRows() {
        Fingerprint oldOwner = insert("owner-a", "other-task", 11L);
        Fingerprint recentOwner = insert("owner-a", null, 22L);
        Fingerprint sharedTask = insert("owner-b", "shared-task", 33L);
        insert("owner-b", "unrelated-task", 44L);
        db.sql("UPDATE content_fingerprint SET created_at=now()-interval '120 days' "
                        + "WHERE id IN (CAST(:oldOwner AS uuid), CAST(:sharedTask AS uuid))")
                .bind("oldOwner", oldOwner.id().toString())
                .bind("sharedTask", sharedTask.id().toString())
                .then().block();

        List<Fingerprint> candidates = repository.findCandidates(
                        "owner-a", "shared-task", Instant.now().minus(90, ChronoUnit.DAYS))
                .collectList().block();

        assertThat(candidates).extracting(Fingerprint::id)
                .containsExactlyInAnyOrder(recentOwner.id(), sharedTask.id());
        assertThat(candidates).noneMatch(value -> value.id().equals(oldOwner.id()));
    }

    private Fingerprint insert(String owner, String task, long hash) {
        return repository.insert(new Fingerprint(
                null, owner, task, "application", "douyin", "video",
                hash, 20, "generation", null)).block();
    }
}
