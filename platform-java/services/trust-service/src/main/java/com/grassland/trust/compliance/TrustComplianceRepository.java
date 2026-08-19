package com.grassland.trust.compliance;

import java.util.Collection;
import java.util.stream.Collectors;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class TrustComplianceRepository {

    private final DatabaseClient db;

    public TrustComplianceRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Long> activeDisputeCount(String accountId, Collection<String> engagementRefs) {
        String refs = engagementRefs == null ? "" : engagementRefs.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .collect(Collectors.joining(","));
        return db.sql("""
                SELECT COUNT(*)::bigint AS active_count FROM dispute_case
                WHERE status <> 'final'
                  AND (opened_by_account_id = CAST(:accountId AS uuid)
                       OR (:refs <> '' AND engagement_ref = ANY(string_to_array(:refs, ','))))
                """)
                .bind("accountId", accountId)
                .bind("refs", refs)
                .map(row -> row.get("active_count", Long.class))
                .one().defaultIfEmpty(0L);
    }
}
