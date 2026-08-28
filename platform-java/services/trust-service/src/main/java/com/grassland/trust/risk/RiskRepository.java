package com.grassland.trust.risk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.trust.risk.RiskModels.CaseAudit;
import com.grassland.trust.risk.RiskModels.Evaluation;
import com.grassland.trust.risk.RiskModels.RegisterSignalRequest;
import com.grassland.trust.risk.RiskModels.RiskCase;
import com.grassland.trust.risk.RiskModels.Signal;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class RiskRepository {
    private static final String SIGNAL_COLS = "id::text, source_kind, source_ref, subject_kind, subject_ref, "
            + "organization_id::text, rule_code, rule_version, score, severity, status, evidence::text, "
            + "occurred_at, created_at, updated_at";
    private static final String CASE_COLS = "id::text, subject_kind, subject_ref, organization_id::text, status, "
            + "severity, score, reason, resolution_note, assigned_to::text, created_at, updated_at, resolved_at";

    /** signals 列表筛选口径（行查与 COUNT 共用，防分页漂移）。 */
    private static final String SIGNAL_LIST_FILTER = "(:status IS NULL OR status=:status) "
            + "AND (:kind IS NULL OR subject_kind=:kind) AND (:ref IS NULL OR subject_ref=:ref)";

    /** cases 列表筛选口径（行查与 COUNT 共用，防分页漂移）。 */
    private static final String CASE_LIST_FILTER = "(:status IS NULL OR status=:status) "
            + "AND (:severity IS NULL OR severity=:severity) "
            + "AND (:kind IS NULL OR subject_kind=:kind) AND (:ref IS NULL OR subject_ref=:ref)";

    private final DatabaseClient db;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public RiskRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<CreatedSignal> createSignal(RegisterSignalRequest request, Evaluation evaluation) {
        String id = UUID.randomUUID().toString();
        String version = RiskRuleEngine.version(request.ruleVersion());
        Instant occurredAt = request.occurredAt() == null ? Instant.now() : request.occurredAt();
        String evidence = json(request.evidence() == null ? Map.of() : request.evidence());
        var spec = db.sql("""
                INSERT INTO risk_signal(id, source_kind, source_ref, subject_kind, subject_ref, organization_id,
                                        rule_code, rule_version, score, severity, evidence, occurred_at)
                VALUES (CAST(:id AS uuid), :sourceKind, :sourceRef, :subjectKind, :subjectRef, CAST(:org AS uuid),
                        :ruleCode, :ruleVersion, :score, :severity, CAST(:evidence AS jsonb), :occurredAt)
                ON CONFLICT (source_kind, source_ref, rule_code, rule_version) DO NOTHING
                RETURNING %s
                """.formatted(SIGNAL_COLS))
                .bind("id", id).bind("sourceKind", request.sourceKind()).bind("sourceRef", request.sourceRef())
                .bind("subjectKind", request.subjectKind()).bind("subjectRef", request.subjectRef())
                .bind("ruleCode", request.ruleCode()).bind("ruleVersion", version)
                .bind("score", evaluation.score()).bind("severity", evaluation.severity())
                .bind("evidence", evidence).bind("occurredAt", occurredAt.atOffset(ZoneOffset.UTC));
        spec = bindNullable(spec, "org", request.organizationId());
        return spec.map(RiskRepository::mapSignal).one().map(signal -> new CreatedSignal(signal, true))
                .switchIfEmpty(findSignal(request.sourceKind(), request.sourceRef(), request.ruleCode(), version)
                        .map(signal -> new CreatedSignal(signal, false)));
    }

    public Mono<Signal> findSignal(String sourceKind, String sourceRef, String ruleCode, String ruleVersion) {
        return db.sql("SELECT " + SIGNAL_COLS + " FROM risk_signal WHERE source_kind=:sk AND source_ref=:sr "
                        + "AND rule_code=:rc AND rule_version=:rv")
                .bind("sk", sourceKind).bind("sr", sourceRef).bind("rc", ruleCode).bind("rv", ruleVersion)
                .map(RiskRepository::mapSignal).one();
    }

    public Mono<Integer> countSignals(String subjectKind, String subjectRef, String ruleCode) {
        return db.sql("SELECT COUNT(*)::int AS count FROM risk_signal WHERE subject_kind=:kind"
                        + " AND subject_ref=:ref AND rule_code=:rule")
                .bind("kind", subjectKind).bind("ref", subjectRef).bind("rule", ruleCode)
                .map(row -> value(row.get("count", Integer.class))).one().defaultIfEmpty(0);
    }

    public Flux<Signal> listSignals(String status, String subjectKind, String subjectRef, int limit, int offset) {
        var spec = db.sql("SELECT " + SIGNAL_COLS + " FROM risk_signal WHERE " + SIGNAL_LIST_FILTER
                        + " ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset")
                .bind("limit", limit).bind("offset", offset);
        spec = bindNullableText(spec, "status", status);
        spec = bindNullableText(spec, "kind", subjectKind);
        spec = bindNullableText(spec, "ref", subjectRef);
        return spec.map(RiskRepository::mapSignal).all();
    }

    /** signals 列表总数（与 {@link #listSignals(String, String, String, int, int)} 同 WHERE 口径）。 */
    public Mono<Long> countListSignals(String status, String subjectKind, String subjectRef) {
        var spec = db.sql("SELECT COUNT(*)::bigint AS c FROM risk_signal WHERE " + SIGNAL_LIST_FILTER);
        spec = bindNullableText(spec, "status", status);
        spec = bindNullableText(spec, "kind", subjectKind);
        spec = bindNullableText(spec, "ref", subjectRef);
        return spec.map(row -> row.get("c", Long.class)).one().defaultIfEmpty(0L);
    }

    public Mono<RiskCase> createOrFindActiveCase(Signal signal, Evaluation evaluation) {
        String id = UUID.randomUUID().toString();
        var spec = db.sql("""
                INSERT INTO risk_case(id, subject_kind, subject_ref, organization_id, severity, score, reason)
                VALUES (CAST(:id AS uuid), :kind, :ref, CAST(:org AS uuid), :severity, :score, :reason)
                ON CONFLICT (subject_kind, subject_ref) WHERE status IN ('open', 'in_review')
                DO UPDATE SET severity = CASE
                        WHEN EXCLUDED.score > risk_case.score THEN EXCLUDED.severity ELSE risk_case.severity END,
                    score = GREATEST(risk_case.score, EXCLUDED.score), updated_at=now()
                RETURNING %s
                """.formatted(CASE_COLS))
                .bind("id", id).bind("kind", signal.subjectKind()).bind("ref", signal.subjectRef())
                .bind("severity", evaluation.severity()).bind("score", evaluation.score())
                .bind("reason", evaluation.reason());
        spec = bindNullable(spec, "org", signal.organizationId());
        return spec.map(RiskRepository::mapCase).one();
    }

    public Mono<Void> attachSignal(String caseId, String signalId) {
        return db.sql("INSERT INTO risk_case_signal(case_id, signal_id) VALUES(CAST(:cid AS uuid), CAST(:sid AS uuid)) "
                        + "ON CONFLICT (signal_id) DO NOTHING")
                .bind("cid", caseId).bind("sid", signalId).then();
    }

    public Flux<RiskCase> listCases(String status, String severity, String subjectKind, String subjectRef,
            int limit, int offset) {
        var spec = db.sql("SELECT " + CASE_COLS + " FROM risk_case WHERE " + CASE_LIST_FILTER
                        + " ORDER BY CASE severity WHEN 'critical' THEN 4 WHEN 'high' THEN 3 "
                        + "WHEN 'medium' THEN 2 ELSE 1 END DESC, created_at, id LIMIT :limit OFFSET :offset")
                .bind("limit", limit).bind("offset", offset);
        spec = bindNullableText(spec, "status", status);
        spec = bindNullableText(spec, "severity", severity);
        spec = bindNullableText(spec, "kind", subjectKind);
        spec = bindNullableText(spec, "ref", subjectRef);
        return spec.map(RiskRepository::mapCase).all();
    }

    /** cases 列表总数（与 {@link #listCases(String, String, String, String, int, int)} 同 WHERE 口径）。 */
    public Mono<Long> countListCases(String status, String severity, String subjectKind, String subjectRef) {
        var spec = db.sql("SELECT COUNT(*)::bigint AS c FROM risk_case WHERE " + CASE_LIST_FILTER);
        spec = bindNullableText(spec, "status", status);
        spec = bindNullableText(spec, "severity", severity);
        spec = bindNullableText(spec, "kind", subjectKind);
        spec = bindNullableText(spec, "ref", subjectRef);
        return spec.map(row -> row.get("c", Long.class)).one().defaultIfEmpty(0L);
    }

    public Mono<RiskCase> findCase(String id) {
        return db.sql("SELECT " + CASE_COLS + " FROM risk_case WHERE id=CAST(:id AS uuid)")
                .bind("id", id).map(RiskRepository::mapCase).one();
    }

    public Flux<Signal> listCaseSignals(String caseId) {
        return db.sql("SELECT " + SIGNAL_COLS.replace("id::text", "s.id::text")
                        + " FROM risk_signal s JOIN risk_case_signal link ON link.signal_id=s.id "
                        + "WHERE link.case_id=CAST(:id AS uuid) ORDER BY s.occurred_at, s.id")
                .bind("id", caseId).map(RiskRepository::mapSignal).all();
    }

    public Mono<RiskCase> transition(String id, String action, String actorAccountId, String note) {
        String assignment = "start_review".equals(action) ? ", assigned_to=CAST(:actor AS uuid)" : "";
        String target = switch (action) {
            case "start_review" -> "in_review";
            case "resolve" -> "resolved";
            case "dismiss" -> "dismissed";
            case "reopen" -> "open";
            default -> throw new IllegalArgumentException("不支持的风控动作");
        };
        String guard = "reopen".equals(action) ? "status IN ('resolved','dismissed')" : "status IN ('open','in_review')";
        String terminal = switch (action) {
            case "resolve", "dismiss" -> ", resolution_note=:note, resolved_at=now()";
            case "reopen" -> ", resolution_note=NULL, resolved_at=NULL, assigned_to=NULL";
            default -> "";
        };
        var spec = db.sql("UPDATE risk_case SET status=:target, updated_at=now()" + assignment + terminal
                        + " WHERE id=CAST(:id AS uuid) AND " + guard + " RETURNING " + CASE_COLS)
                .bind("target", target).bind("id", id);
        if ("start_review".equals(action)) spec = bindNullable(spec, "actor", actorAccountId);
        if ("resolve".equals(action) || "dismiss".equals(action)) spec = bindNullableText(spec, "note", note);
        return spec.map(RiskRepository::mapCase).one();
    }

    public Mono<Long> appendAudit(String caseId, String action, String actorAccountId, String actorRole, String note) {
        var spec = db.sql("INSERT INTO risk_case_audit(case_id, action, actor_account_id, actor_role, note) "
                        + "VALUES(CAST(:cid AS uuid), :action, CAST(:actor AS uuid), :role, :note) RETURNING id")
                .bind("cid", caseId).bind("action", action).bind("role", actorRole);
        spec = bindNullable(spec, "actor", actorAccountId);
        spec = bindNullableText(spec, "note", note);
        return spec.map(row -> row.get("id", Long.class)).one();
    }

    public Flux<CaseAudit> listAudits(String caseId) {
        return db.sql("SELECT id, case_id::text, action, actor_account_id::text, actor_role, note, created_at "
                        + "FROM risk_case_audit WHERE case_id=CAST(:id AS uuid) ORDER BY id")
                .bind("id", caseId).map(row -> new CaseAudit(row.get("id", Long.class),
                        row.get("case_id", String.class), row.get("action", String.class),
                        row.get("actor_account_id", String.class), row.get("actor_role", String.class),
                        row.get("note", String.class), instant(row.get("created_at", OffsetDateTime.class)))).all();
    }

    private String json(Map<String, Object> value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("evidence 不是合法 JSON"); }
    }

    private static int value(Integer value) { return value == null ? 0 : value; }

    private static Signal mapSignal(Readable row) {
        return new Signal(row.get("id", String.class), row.get("source_kind", String.class),
                row.get("source_ref", String.class), row.get("subject_kind", String.class),
                row.get("subject_ref", String.class), row.get("organization_id", String.class),
                row.get("rule_code", String.class), row.get("rule_version", String.class),
                integer(row.get("score", Integer.class)), row.get("severity", String.class),
                row.get("status", String.class), row.get("evidence", String.class),
                instant(row.get("occurred_at", OffsetDateTime.class)), instant(row.get("created_at", OffsetDateTime.class)),
                instant(row.get("updated_at", OffsetDateTime.class)));
    }

    private static RiskCase mapCase(Readable row) {
        return new RiskCase(row.get("id", String.class), row.get("subject_kind", String.class),
                row.get("subject_ref", String.class), row.get("organization_id", String.class),
                row.get("status", String.class), row.get("severity", String.class),
                integer(row.get("score", Integer.class)), row.get("reason", String.class),
                row.get("resolution_note", String.class), row.get("assigned_to", String.class),
                instant(row.get("created_at", OffsetDateTime.class)), instant(row.get("updated_at", OffsetDateTime.class)),
                instant(row.get("resolved_at", OffsetDateTime.class)));
    }

    private static int integer(Integer value) { return value == null ? 0 : value; }
    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return value == null || value.isBlank() ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
    private static GenericExecuteSpec bindNullableText(GenericExecuteSpec spec, String name, String value) {
        return value == null || value.isBlank() ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    public record CreatedSignal(Signal signal, boolean created) {}
}
