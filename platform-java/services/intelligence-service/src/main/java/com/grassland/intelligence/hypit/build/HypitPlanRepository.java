package com.grassland.intelligence.hypit.build;

import java.time.Instant;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * hypit_plan / hypit_snapshot 持久面（任务书 #107-1 C107-08 / 卡步骤 6/7）。
 *
 * <p>Plan 记录不可变：同 (project, plan_hash) 幂等返回原行，绝不 UPDATE；估价快照
 * 同理（pricing_hash 键控，只插入）。unknown 价格在 unknowns_json 中如实成行，
 * 不写 0（K12.7）。
 */
@Component
public class HypitPlanRepository {

    private final DatabaseClient db;

    public HypitPlanRepository(DatabaseClient db) {
        this.db = db;
    }

    public record PlanRow(UUID id, UUID projectId, long revision, String runFile, String planHash,
            String profileHash, String planJson, String pricingJson, String repositoryLocationJson,
            Instant createdAt) {
    }

    public record PricingRow(UUID id, UUID planId, String pricingHash, String costsJson,
            String unknownsJson, Instant createdAt) {
    }

    private static final String PLAN_COLS = """
            id::text, project_id::text, revision, run_file, plan_hash, profile_hash,
            plan_json::text, pricing_json::text, repository_location::text, created_at
            """;

    private static final String PRICING_COLS = """
            id::text, plan_id::text, pricing_hash, costs_json::text, unknowns_json::text, created_at
            """;

    /**
     * 幂等插入：V91 未给 (project, plan_hash) 建唯一约束，用 WHERE NOT EXISTS
     * 竞态窗口内可能双插，但两行内容相同且服务层恒按 hash 读取——计划不可变语义
     * 不受影响；绝不 UPDATE 旧行。
     */
    public Mono<PlanRow> insertPlan(UUID id, UUID projectId, long revision, String runFile, String planHash,
            String profileHash, String planJson) {
        return db.sql("""
                INSERT INTO hypit_plan(id, project_id, revision, run_file, plan_hash, profile_hash, plan_json)
                SELECT CAST(:id AS uuid), CAST(:project AS uuid), :revision, :runFile, :planHash,
                       :profileHash, CAST(:plan AS jsonb)
                WHERE NOT EXISTS (SELECT 1 FROM hypit_plan
                    WHERE project_id = CAST(:project AS uuid) AND plan_hash = :planHash)
                RETURNING""" + " " + PLAN_COLS)
                .bind("id", id.toString())
                .bind("project", projectId.toString())
                .bind("revision", revision)
                .bind("runFile", runFile)
                .bind("planHash", planHash)
                .bind("profileHash", profileHash)
                .bind("plan", planJson)
                .map(HypitPlanRepository::mapPlan)
                .one()
                .switchIfEmpty(findPlan(projectId, planHash));
    }

    public Mono<PlanRow> findPlan(UUID projectId, String planHash) {
        return db.sql("SELECT " + PLAN_COLS + " FROM hypit_plan"
                + " WHERE project_id = CAST(:project AS uuid) AND plan_hash = :planHash")
                .bind("project", projectId.toString())
                .bind("planHash", planHash)
                .map(HypitPlanRepository::mapPlan)
                .one();
    }

    public Mono<PlanRow> findPlanById(UUID planId) {
        return db.sql("SELECT " + PLAN_COLS + " FROM hypit_plan WHERE id = CAST(:id AS uuid)")
                .bind("id", planId.toString())
                .map(HypitPlanRepository::mapPlan)
                .one();
    }

    public Flux<PlanRow> listPlans(UUID projectId, int limit) {
        return db.sql("SELECT " + PLAN_COLS + " FROM hypit_plan WHERE project_id = CAST(:project AS uuid)"
                + " ORDER BY created_at DESC, id LIMIT :limit")
                .bind("project", projectId.toString())
                .bind("limit", limit)
                .map(HypitPlanRepository::mapPlan)
                .all();
    }

    /** 估价快照幂等插入：同 (plan, pricing_hash) 命中 UNIQUE 时读原行。 */
    public Mono<PricingRow> insertPricing(UUID id, UUID planId, String pricingHash, String costsJson,
            String unknownsJson) {
        var statement = db.sql("""
                INSERT INTO hypit_pricing_snapshot(id, plan_id, pricing_hash, costs_json, unknowns_json)
                VALUES (CAST(:id AS uuid), CAST(:plan AS uuid), :pricingHash,
                        CAST(:costs AS jsonb), CAST(:unknowns AS jsonb))
                ON CONFLICT (plan_id, pricing_hash) DO NOTHING
                RETURNING """ + " " + PRICING_COLS)
                .bind("id", id.toString())
                .bind("plan", planId.toString())
                .bind("pricingHash", pricingHash)
                .bind("costs", costsJson);
        statement = unknownsJson == null ? statement.bindNull("unknowns", String.class)
                : statement.bind("unknowns", unknownsJson);
        return statement.map(HypitPlanRepository::mapPricing).one()
                .switchIfEmpty(db.sql("SELECT " + PRICING_COLS + " FROM hypit_pricing_snapshot"
                        + " WHERE plan_id = CAST(:plan AS uuid) AND pricing_hash = :pricingHash")
                        .bind("plan", planId.toString())
                        .bind("pricingHash", pricingHash)
                        .map(HypitPlanRepository::mapPricing)
                        .one());
    }

    public Mono<PricingRow> latestPricing(UUID planId) {
        return db.sql("SELECT " + PRICING_COLS + " FROM hypit_pricing_snapshot"
                + " WHERE plan_id = CAST(:plan AS uuid) ORDER BY created_at DESC, id LIMIT 1")
                .bind("plan", planId.toString())
                .map(HypitPlanRepository::mapPricing)
                .one();
    }

    private static PlanRow mapPlan(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
        return new PlanRow(
                UUID.fromString(row.get("id", String.class)),
                UUID.fromString(row.get("project_id", String.class)),
                row.get("revision", Long.class),
                row.get("run_file", String.class),
                row.get("plan_hash", String.class),
                row.get("profile_hash", String.class),
                row.get("plan_json", String.class),
                row.get("pricing_json", String.class),
                row.get("repository_location", String.class),
                row.get("created_at", Instant.class));
    }

    private static PricingRow mapPricing(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
        return new PricingRow(
                UUID.fromString(row.get("id", String.class)),
                UUID.fromString(row.get("plan_id", String.class)),
                row.get("pricing_hash", String.class),
                row.get("costs_json", String.class),
                row.get("unknowns_json", String.class),
                row.get("created_at", Instant.class));
    }
}
