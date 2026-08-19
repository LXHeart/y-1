package com.grassland.intelligence.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IntelligenceComplianceRepositoryIT extends IntelligenceItSupport {

    @Autowired
    private IntelligenceComplianceRepository repository;

    @Test
    void personalMediaRemainsVisibleToGarbageCollectorAfterErasureIsQueued() {
        String accountId = UUID.randomUUID().toString();
        String mediaId = UUID.randomUUID().toString();
        db.sql("""
                        INSERT INTO media_reference(id, owner_account_id, purpose, object_key,
                                                    mime_type, size_bytes, source, status)
                        VALUES (CAST(:mediaId AS uuid), :accountId, 'user_upload', :objectKey,
                                'image/png', 128, 'upload', 'active')
                        """)
                .bind("mediaId", mediaId).bind("accountId", accountId)
                .bind("objectKey", "compliance/" + mediaId).then().block();

        Map<String, Long> counts = repository.erasePii(accountId).block();
        Map<String, Object> row = db.sql("SELECT status, deleted_at FROM media_reference"
                        + " WHERE id = CAST(:id AS uuid)")
                .bind("id", mediaId).fetch().one().block();

        assertThat(counts).containsEntry("mediaQueuedForDeletion", 1L);
        assertThat(row).isNotNull();
        assertThat(row.get("status")).isEqualTo("deleting");
        assertThat(row.get("deleted_at")).isNull();
    }
}
