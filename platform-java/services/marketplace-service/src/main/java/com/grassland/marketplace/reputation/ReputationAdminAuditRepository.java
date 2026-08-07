package com.grassland.marketplace.reputation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 等级配置与 Lv5 邀请的只追加审计仓储。 */
@Component
public class ReputationAdminAuditRepository {

    private final DatabaseClient db;
    private final ObjectMapper objectMapper;

    public ReputationAdminAuditRepository(DatabaseClient db, ObjectMapper objectMapper) {
        this.db = db;
        this.objectMapper = objectMapper;
    }

    public Mono<Long> append(String action, String targetAccountId, String actorAccountId,
                             String actorRole, Long policyVersion, Long admissionVersion, String note,
                             Map<String, Object> beforeSnapshot, Map<String, Object> afterSnapshot) {
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO reputation_admin_audit(
                    action, target_account_id, actor_account_id, actor_role,
                    policy_version, admission_version, note, before_snapshot, after_snapshot)
                VALUES (:action, CAST(:target AS uuid), CAST(:actor AS uuid), :role,
                        :policyVersion, :admissionVersion, :note,
                        CAST(:beforeSnapshot AS jsonb), CAST(:afterSnapshot AS jsonb))
                RETURNING id
                """).bind("action", action).bind("actor", actorAccountId).bind("role", actorRole);
        spec = bindNullable(spec, "target", targetAccountId, String.class);
        spec = bindNullable(spec, "policyVersion", policyVersion, Long.class);
        spec = bindNullable(spec, "admissionVersion", admissionVersion, Long.class);
        spec = bindNullable(spec, "note", note, String.class);
        spec = spec.bind("beforeSnapshot", writeSnapshot(beforeSnapshot));
        spec = spec.bind("afterSnapshot", writeSnapshot(afterSnapshot));
        return spec.map(row -> row.get("id", Long.class)).one();
    }

    private String writeSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("审计快照不能为空");
        }
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("审计快照无法序列化", error);
        }
    }

    private static <T> GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name,
                                                        T value, Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }
}
