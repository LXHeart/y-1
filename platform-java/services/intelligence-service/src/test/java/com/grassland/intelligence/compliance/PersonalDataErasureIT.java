package com.grassland.intelligence.compliance;

import static com.grassland.identity.assertion.TestAssertionHelper.serviceSigner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

/**
 * 分阶段清理 IT（任务书 #103 C103-09；归属统一反例见 #104 C104-01）。
 *
 * <p>
 * 覆盖：§7.4 全资源 fixture 的 manifest→批次→verify（个人正文清零、组织内容/组织 BYOK/计费事实保留、媒体 标记
 * deleting 且对象入册）；对象未清 → erased=false objects_pending（Identity 不写 pii_erased 的前提）； 对象清空 →
 * completed erased=true；同 manifest 幂等（计数不重复）；空数据直接完成；无受控 gate 任务 → 409 拒绝； 超批次资源分批收敛。
 *
 * <p>
 * #104 TC104-01-01～06：组织父链子表（source/proposal/plan/export/canvas/sync/mapping/apply）保留； 分镜谱系与
 * v1/v2 图卡操作归属；本人孤儿清理/无主行不动/他人父保留；多父链个人账号冲突与未知 studio_apply kind 阻止
 * verified；批次断点续跑与幂等；冻结与个人写并发屏障；空/非法账号与对照账号边界。
 */
class PersonalDataErasureIT extends IntelligenceItSupport {

    @Test
    void incompleteManifestInventoryCannotClaimAccountErased() {
        String account = "incomplete-" + UUID.randomUUID();
        UUID request = UUID.randomUUID();
        lifecycle.prepare(account, request).block();
        var manifest = erasure.plan(account, request).block();
        UUID id = manifest.id();
        db.sql("DELETE FROM personal_data_erasure_step WHERE manifest_id=:id").bind("id", id).then().block();
        var receipt = erasure.verify(id).block();
        assertThat(receipt.erased()).isFalse();
        assertThat(receipt.state()).isEqualTo("needs_review");
        assertThat(lifecycle.find(account).block().state()).isEqualTo("erasing");
    }

    @DynamicPropertySource
    static void erasureProps(DynamicPropertyRegistry registry) {
		// 调度 worker 与测试内直驱 service 会并发抢同一 manifest（端点 verify 读到中间态）——
		// IT 一律关掉调度，由测试直接驱动；worker 行为由 findActiveManifests 租约跳过保证。
		registry.add("intelligence.erasure.enabled", () -> "false");
	}

    @Autowired
    private IntelligenceAccountLifecycleRepository lifecycle;

    @Autowired
    private PersonalDataErasureService erasure;

    private String identityServiceAssertion() {
        Instant now = Instant.now();
        return serviceSigner("identity", "grassland-intelligence")
                .sign(new IdentityAssertion("service:identity", null, null, null, null, "service", "internal", null,
                        "r", "t", "grassland-intelligence", now, now.plusSeconds(30), "service", "identity"));
    }

    /** TC103-09-01 + 04：全资源清理与个人/组织/计费边界；TC103-09-03 前半：对象未清不完成。 */
    @Test
    void fullResourceErasureKeepsOrgScopeAndBillingFactsUntilObjectsCleared() {
        String account = "erase-" + UUID.randomUUID();
        UUID request = UUID.randomUUID();
        String other = "other-" + UUID.randomUUID();
        String org = "org-" + UUID.randomUUID();
        seedFullPersonalFixture(account, other, org);

        // 无 gate → 409（未到保留期不得任意清理）。
        client().post().uri("/internal/compliance/accounts/" + account + "/erase")
                .header("X-Grassland-Identity", identityServiceAssertion()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("closureRequestId", request.toString())).exchange().expectStatus().isEqualTo(409);

        // 冻结后清理：DB 批次全部完成，但对象未物删 → erased=false / objects_pending。
        lifecycle.prepare(account, request).block();
        Map<String, Object> first = erase(account, request.toString());
        assertThat(first.get("erased")).isEqualTo(false);
        assertThat(first.get("state")).isEqualTo("objects_pending");
        assertThat(((Number) first.get("pendingObjects")).longValue()).isGreaterThan(0);
        String manifestId = (String) first.get("manifestId");
        assertThat(manifestId).isNotBlank();

        // TC103-09-01：个人正文应清项归零（组织草稿对照行保留，保留断言见下段与 TC104-01-01）。
        assertThat(count(
                "SELECT count(*) FROM creation_draft WHERE owner_account_id = :a" + " AND organization_id IS NULL",
                account)).isZero();
        assertThat(count("SELECT count(*) FROM creation_draft_version WHERE draft_id IN"
                + " (SELECT id FROM creation_draft WHERE owner_account_id = :a" + " AND organization_id IS NULL)",
                account)).isZero();
        // 组织草稿子表保留（TC104-01-01 前置：fixture 在组织草稿下各放一行）。
        assertThat(count("SELECT count(*) FROM creation_source_document WHERE owner_account_id = :a", account))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_text_proposal WHERE owner_account_id = :a", account))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_visual_plan WHERE owner_account_id = :a", account))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_export WHERE owner_account_id = :a", account)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_canvas_document WHERE account_id = :a", account)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_wechat_draft_sync WHERE owner_account_id = :a", account))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_wechat_media_mapping WHERE owner_account_id = :a", account))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_studio_apply WHERE owner_account_id = :a", account))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_wechat_account WHERE owner_account_id = :a", account)).isZero();
        assertThat(count("SELECT count(*) FROM video_storyboard WHERE account_id = :a" + " AND organization_id IS NULL",
                account)).isZero();
        assertThat(count("SELECT count(*) FROM video_shot_take WHERE shot_id IN (SELECT sh.id FROM video_shot sh"
                + " JOIN video_storyboard s ON s.id = sh.storyboard_id WHERE s.account_id = :a"
                + " AND s.organization_id IS NULL)", account)).isZero();
        assertThat(count(
                "SELECT count(*) FROM content_asset WHERE owner_account_id = :a" + " AND library_type = 'personal'",
                account)).isZero();
        assertThat(count(
                "SELECT count(*) FROM ai_provider_key WHERE owner_account_id = :a" + " AND organization_id IS NULL",
                account)).isZero();
        assertThat(count("SELECT count(*) FROM speech_transcription WHERE owner_account_id = :a", account)).isZero();
        assertThat(count("SELECT count(*) FROM content_fingerprint WHERE owner_account_id = :a", account)).isZero();
        assertThat(count("SELECT count(*) FROM creation_generation WHERE owner_account_id = :a", account)).isZero();
        // 计费事实脱敏保留：ai_run 行保留、错误文本清空；经济键（operation_id）在。
        assertThat(count("SELECT count(*) FROM ai_run WHERE account_id = :a AND failure_reason IS NOT NULL", account))
                .isZero();
        assertThat(count("SELECT count(*) FROM ai_run WHERE account_id = :a", account)).isEqualTo(1);
        assertThat(count(
                "SELECT count(*) FROM video_generation_job WHERE account_id = :a" + " AND input_payload <> '{}'::jsonb",
                account)).isZero();
        // 媒体保留行供 GC（对象物删归 C103-10）且对象已入册。
        assertThat(
                count("SELECT count(*) FROM media_reference WHERE owner_account_id = :a" + " AND status = 'deleting'",
                        account))
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM personal_data_erasure_object WHERE manifest_id = CAST(:a AS uuid)"
                + " AND state = 'pending'", manifestId)).isGreaterThanOrEqualTo(3);

        // TC103-09-04：组织内容 / 组织 BYOK / 他人资产 / 他人媒体保留。
        assertThat(count(
                "SELECT count(*) FROM creation_draft WHERE owner_account_id = :a" + " AND organization_id IS NOT NULL",
                account)).isEqualTo(1);
        assertThat(count(
                "SELECT count(*) FROM ai_provider_key WHERE owner_account_id = :a" + " AND organization_id IS NOT NULL",
                account)).isEqualTo(1);
        assertThat(count(
                "SELECT count(*) FROM content_asset WHERE owner_account_id = :a" + " AND library_type = 'merchant'",
                account)).isEqualTo(1);
        assertThat(count(
                "SELECT count(*) FROM video_storyboard WHERE account_id = :a" + " AND organization_id IS NOT NULL",
                account)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_draft WHERE owner_account_id = :a", other)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM media_reference WHERE owner_account_id = :a", other)).isEqualTo(1);
        // 他人授予本账号的授权撤销。
        assertThat(count("SELECT count(*) FROM content_asset_grant WHERE grantee_account_id = :a", account)).isZero();
        assertThat(count("SELECT count(*) FROM content_asset_grant WHERE grantee_account_id = :a", other)).isEqualTo(1);

        // 模拟 C103-10 对象物删 → 再清理 → completed / erased=true（verified 回执才真实）。
        db.sql("UPDATE personal_data_erasure_object SET state = 'deleted', object_key = NULL"
                + " WHERE manifest_id = CAST(:m AS uuid)").bind("m", manifestId).then().block();
        Map<String, Object> done = erase(account, request.toString());
        assertThat(done.get("erased")).isEqualTo(true);
        assertThat(done.get("state")).isEqualTo("completed");
        assertThat(done.get("manifestId")).isEqualTo(manifestId);
        assertThat((String) done.get("verifiedAt")).isNotBlank();
        assertThat(lifecycle.find(account).block().state()).isEqualTo("erased");

        // TC103-09-02 幂等：同 manifest 重复回执不重复计数、不新建 manifest。
        Map<String, Object> again = erase(account, request.toString());
        assertThat(again.get("manifestId")).isEqualTo(manifestId);
        assertThat(again.get("erased")).isEqualTo(true);
        Long manifests = count(
                "SELECT count(*) FROM personal_data_erasure_manifest" + " WHERE closure_request_id = CAST(:a AS uuid)",
                request.toString());
        assertThat(manifests).isEqualTo(1);
    }

    /** TC103-09-01 空数据 + TC103-09-05 已完成账号只读回放：无资源直接 completed，重复幂等。 */
    @Test
    void emptyAccountErasesImmediatelyAndReplaysIdempotently() {
        String account = "erase-empty-" + UUID.randomUUID();
        UUID request = UUID.randomUUID();
        lifecycle.prepare(account, request).block();

        Map<String, Object> receipt = erase(account, request.toString());
        assertThat(receipt.get("erased")).as("receipt=%s", receipt).isEqualTo(true);
        assertThat(receipt.get("state")).isEqualTo("completed");
        assertThat(((Number) receipt.get("pendingObjects")).longValue()).isZero();
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) receipt.get("counts");
        assertThat(counts.values().stream().mapToLong(v -> ((Number) v).longValue()).sum()).isZero();

        Map<String, Object> replay = erase(account, request.toString());
        assertThat(replay.get("manifestId")).isEqualTo(receipt.get("manifestId"));
        assertThat(replay.get("erased")).isEqualTo(true);
    }

    /** TC103-09-02 分批：超 200 条资源分批收敛、游标单调、计数不重复。 */
    @Test
    void oversizeResourcesConvergeAcrossBatches() {
        String account = "erase-batch-" + UUID.randomUUID();
        UUID request = UUID.randomUUID();
        for (int i = 0; i < 250; i++) {
            db.sql("INSERT INTO creation_draft(id, owner_account_id, title, source_type, created_at, updated_at)"
                    + " VALUES (gen_random_uuid(), :a, '批次草稿" + i + "', 'independent', now(), now())")
                    .bind("a", account).then().block();
        }
        lifecycle.prepare(account, request).block();

        Map<String, Object> receipt = erase(account, request.toString());
        assertThat(receipt.get("erased")).as("receipt=%s", receipt).isEqualTo(true);
        assertThat(receipt.get("state")).isEqualTo("completed");
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) receipt.get("counts");
        assertThat(((Number) counts.get("creation_draft")).longValue()).isEqualTo(250L);
        assertThat(count("SELECT count(*) FROM creation_draft WHERE owner_account_id = :a", account)).isZero();
    }

    /**
     * TC104-01-01 混合草稿族：A 的个人草稿与 O1 组织草稿（owner 同为 A）各含来源/建议/计划（revision+quote）/导出/
     * apply/canvas/sync（含 export 双链）/mapping；C 对照同族。清理后个人族归零，组织族与 C 原样，retained 不算残留。
     */
    @Test
    void orgDraftChildrenAcrossFamiliesAreRetainedWhilePersonalErase() {
        String account = "t10401a-" + UUID.randomUUID();
        String other = "t10401c-" + UUID.randomUUID();
        UUID request = UUID.randomUUID();
        String hash = "h".repeat(64);
        String personalDraft = UUID.randomUUID().toString();
        String orgDraft = UUID.randomUUID().toString();
        String otherDraft = UUID.randomUUID().toString();
        insertDraft(personalDraft, account, null);
        insertDraft(orgDraft, account, "org-o1");
        insertDraft(otherDraft, other, null);
        String personalPlan = insertVisualPlan(account, personalDraft, "req-104a");
        String orgPlan = insertVisualPlan(account, orgDraft, "req-104b");
        insertSourceDocument(account, personalDraft, "req-104c");
        insertSourceDocument(account, orgDraft, "req-104d");
        insertSourceDocument(other, otherDraft, "req-104e");
        insertTextProposal(account, personalDraft, "req-104f");
        insertTextProposal(account, orgDraft, "req-104g");
        insertPlanRevision(personalPlan);
        insertPlanRevision(orgPlan);
        insertVisualQuote(account, personalPlan, "req-104h");
        insertVisualQuote(account, orgPlan, "req-104i");
        String personalExport = insertExport(account, personalDraft, "req-104j");
        String orgExport = insertExport(account, orgDraft, "req-104k");
        insertStudioApply(account, "plan-patch", personalPlan, "req-104l");
        insertStudioApply(account, "plan-patch", orgPlan, "req-104m");
        insertCanvasDocument(account, personalDraft);
        insertCanvasDocument(account, orgDraft);
        // 组织 sync 双链（draft 直链 + export 链均指组织草稿）与个人 sync。
        String orgSync = insertWechatSync(account, orgDraft, orgExport, "req-104n");
        insertWechatSync(account, personalDraft, personalExport, "req-104o");
        insertWechatMapping(account, orgSync, "wechat/org-derived");
        insertWechatMapping(account, UUID.randomUUID().toString(), "wechat/personal-derived");

        lifecycle.prepare(account, request).block();
        Map<String, Object> receipt = erase(account, request.toString());
        // 行级收口（对象保护断言归 C104-02）；本 fixture 对象=个人导出无 manifest、组织/个人 mapping 派生 key。
        assertThat(receipt.get("state")).isEqualTo("objects_pending");
        String manifestId = (String) receipt.get("manifestId");
        db.sql("UPDATE personal_data_erasure_object SET state='deleted', object_key=NULL"
                + " WHERE manifest_id = CAST(:m AS uuid)").bind("m", manifestId).then().block();
        Map<String, Object> done = erase(account, request.toString());
        assertThat(done.get("erased")).isEqualTo(true);
        assertThat(done.get("state")).isEqualTo("completed");

        // 个人族归零：owner=A 的行只剩组织父链下的。
        assertThat(count("SELECT count(*) FROM creation_source_document WHERE owner_account_id = :a", account))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_text_proposal WHERE owner_account_id = :a", account))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_visual_plan WHERE owner_account_id = :a", account)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_visual_plan_revision WHERE plan_id IN"
                + " (SELECT id FROM creation_visual_plan WHERE owner_account_id = :a)", account)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_visual_quote WHERE owner_account_id = :a", account))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_export WHERE owner_account_id = :a", account)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_studio_apply WHERE owner_account_id = :a", account))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_canvas_document WHERE account_id = :a", account)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_wechat_draft_sync WHERE owner_account_id = :a", account))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_wechat_media_mapping WHERE owner_account_id = :a", account))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_draft WHERE owner_account_id = :a", account)).isEqualTo(1);
        // C 对照原样。
        assertThat(count("SELECT count(*) FROM creation_source_document WHERE owner_account_id = :a", other))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_draft WHERE owner_account_id = :a", other)).isEqualTo(1);
    }

    /**
     * TC104-01-02 分镜和卡图谱系：个人链全删；个人分镜经工作区挂组织草稿（含其镜头/任务/变体/agent 计划）保留； v1
     * 图卡操作孤儿与 plan 个人链操作删除，组织链 v2 操作保留。
     */
    @Test
    void storyboardAndCardGenealogyScopes() {
        String account = "t10402-" + UUID.randomUUID();
        UUID request = UUID.randomUUID();
        String hash = "h".repeat(64);
        String personalDraft = UUID.randomUUID().toString();
        String orgDraft = UUID.randomUUID().toString();
        insertDraft(personalDraft, account, null);
        insertDraft(orgDraft, account, "org-g");
        String personalPlan = insertVisualPlan(account, personalDraft, "req-g1");
        String orgPlan = insertVisualPlan(account, orgDraft, "req-g2");

        String sb1 = seedStoryboard(account, null, hash);
        String sb2 = seedStoryboard(account, null, hash);
        // sb1 → 个人草稿；sb2 → 组织草稿（个人分镜被组织项目引用）。
        insertWorkspace(sb1, personalDraft, account);
        insertWorkspace(sb2, orgDraft, account);
        insertVariant(sb1, account);
        insertVariant(sb2, account);
        insertAgentPlan(account, personalDraft, sb1);
        insertAgentPlan(account, personalDraft, sb2);
        // 图卡操作：v1 无链、v2 组织草稿链、v2 个人 plan 链（各带一个 item）。
        String v1op = insertCardOperation(account, null, null, "req-g3");
        String v2org = insertCardOperation(account, orgDraft, null, "req-g4");
        String v2plan = insertCardOperation(account, null, personalPlan, "req-g5");
        insertVisualItem(v1op, "i1");
        insertVisualItem(v2org, "i2");
        insertVisualItem(v2plan, "i3");
        insertVisualArtifact(account, personalDraft, personalPlan);

        lifecycle.prepare(account, request).block();
        Map<String, Object> receipt = erase(account, request.toString());
        assertThat(receipt.get("erased")).as("receipt=%s", receipt).isEqualTo(true);
        assertThat(receipt.get("state")).isEqualTo("completed");

        assertThat(count("SELECT count(*) FROM video_storyboard WHERE id = CAST(:a AS uuid)", sb1)).isZero();
        assertThat(count("SELECT count(*) FROM video_storyboard WHERE id = CAST(:a AS uuid)", sb2)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM video_shot WHERE storyboard_id = CAST(:a AS uuid)", sb1)).isZero();
        assertThat(count("SELECT count(*) FROM video_shot WHERE storyboard_id = CAST(:a AS uuid)", sb2)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM video_production_task WHERE storyboard_id = CAST(:a AS uuid)", sb1))
                .isZero();
        assertThat(count("SELECT count(*) FROM video_production_task WHERE storyboard_id = CAST(:a AS uuid)", sb2))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM video_storyboard_workspace WHERE storyboard_id = CAST(:a AS uuid)",
                sb1)).isZero();
        assertThat(count("SELECT count(*) FROM video_storyboard_workspace WHERE storyboard_id = CAST(:a AS uuid)",
                sb2)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM video_storyboard_variant WHERE parent_storyboard_id = CAST(:a AS uuid)",
                sb1)).isZero();
        assertThat(count("SELECT count(*) FROM video_storyboard_variant WHERE parent_storyboard_id = CAST(:a AS uuid)",
                sb2)).isEqualTo(1);
        // sb2 引用链上的 agent 计划保留（总数=1 即 sb2 链那条）；sb1 链上的删除。
        assertThat(count("SELECT count(*) FROM creation_canvas_agent_plan WHERE account_id = :a", account)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM card_series_operation WHERE id = CAST(:a AS uuid)", v1op)).isZero();
        assertThat(count("SELECT count(*) FROM card_series_operation WHERE id = CAST(:a AS uuid)", v2org)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM card_series_operation WHERE id = CAST(:a AS uuid)", v2plan)).isZero();
        assertThat(count("SELECT count(*) FROM creation_visual_item WHERE operation_id = CAST(:a AS uuid)", v1op))
                .isZero();
        assertThat(count("SELECT count(*) FROM creation_visual_item WHERE operation_id = CAST(:a AS uuid)", v2org))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_visual_item WHERE operation_id = CAST(:a AS uuid)", v2plan))
                .isZero();
        assertThat(count("SELECT count(*) FROM creation_visual_artifact WHERE owner_account_id = :a", account))
                .isZero();
    }

    /**
     * TC104-01-03a 孤儿与归属：本人 owner 无父（source/export/canvas/agent_plan/quote/sync）清理；无 owner 无父行 不猜不动；
     * 他人个人父对象保留（benign，manifest 仍可完成）。
     */
    @Test
    void orphanRowsAreCleanedWhileUnownedAndOtherParentRowsStay() {
        String account = "t10403a-" + UUID.randomUUID();
        String other = "t10403c-" + UUID.randomUUID();
        UUID request = UUID.randomUUID();
        String hash = "h".repeat(64);
        String otherDraft = UUID.randomUUID().toString();
        insertDraft(otherDraft, other, null);
        // 本人 owner 孤儿（父 uuid 不存在）。
        insertSourceDocument(account, UUID.randomUUID().toString(), "req-o1");
        insertExport(account, UUID.randomUUID().toString(), "req-o2");
        insertCanvasDocument(account, UUID.randomUUID().toString());
        insertAgentPlan(account, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        insertVisualQuote(account, UUID.randomUUID().toString(), "req-o3");
        insertWechatSync(account, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "req-o4");
        // 他人个人父：owner=A 的建议挂在 C 的草稿下 → 保留。
        insertTextProposal(account, otherDraft, "req-o5");
        // 无 owner 无父：revision/version 指向不存在行 → 不猜账号，不动。
        db.sql("INSERT INTO creation_visual_plan_revision(plan_id, revision, document_json, document_hash)"
                + " VALUES (CAST(:p AS uuid), 1, '{}'::jsonb, CAST(:h AS char(64)))")
                .bind("p", UUID.randomUUID().toString()).bind("h", hash).then().block();
        db.sql("INSERT INTO creation_draft_version(draft_id, version, title, source_type, status, snapshotted_by)"
                + " VALUES (CAST(:d AS uuid), 1, '无主版本', 'independent', 'draft', :a)")
                .bind("d", UUID.randomUUID().toString()).bind("a", account).then().block();

        lifecycle.prepare(account, request).block();
        Map<String, Object> receipt = erase(account, request.toString());
        assertThat(receipt.get("erased")).as("receipt=%s", receipt).isEqualTo(true);
        assertThat(receipt.get("state")).isEqualTo("completed");

        assertThat(count("SELECT count(*) FROM creation_source_document WHERE owner_account_id = :a", account))
                .isZero();
        assertThat(count("SELECT count(*) FROM creation_export WHERE owner_account_id = :a", account)).isZero();
        assertThat(count("SELECT count(*) FROM creation_canvas_document WHERE account_id = :a", account)).isZero();
        assertThat(count("SELECT count(*) FROM creation_canvas_agent_plan WHERE account_id = :a", account)).isZero();
        assertThat(count("SELECT count(*) FROM creation_visual_quote WHERE owner_account_id = :a", account)).isZero();
        assertThat(count("SELECT count(*) FROM creation_wechat_draft_sync WHERE owner_account_id = :a", account))
                .isZero();
        assertThat(count("SELECT count(*) FROM creation_text_proposal WHERE owner_account_id = :a", account))
                .isEqualTo(1);
        assertThat(countRaw("SELECT count(*) FROM creation_visual_plan_revision r WHERE NOT EXISTS"
                + " (SELECT 1 FROM creation_visual_plan p WHERE p.id = r.plan_id)")).isEqualTo(1);
        assertThat(countRaw("SELECT count(*) FROM creation_draft_version v WHERE NOT EXISTS"
                + " (SELECT 1 FROM creation_draft d WHERE d.id = v.draft_id)")).isEqualTo(1);
    }

    /**
     * TC104-01-03b 冲突：agent 计划双父链个人账号不一致、未知 studio_apply kind → 行保留且 manifest needs_review； 消除冲突后
     * 重跑完成。
     */
    @Test
    void ownershipConflictsBlockVerifiedUntilResolved() {
        String account = "t10403b-" + UUID.randomUUID();
        String other = "t10403d-" + UUID.randomUUID();
        UUID request = UUID.randomUUID();
        String hash = "h".repeat(64);
        String ownDraft = UUID.randomUUID().toString();
        insertDraft(ownDraft, account, null);
        String otherStoryboard = seedStoryboard(other, null, hash);
        insertAgentPlan(account, ownDraft, otherStoryboard);
        db.sql("INSERT INTO creation_studio_apply(id, owner_account_id, request_id, request_hash, kind,"
                + " resource_id, applied_draft_version) VALUES (gen_random_uuid(), :a, 'req-x1',"
                + " CAST(:h AS char(64)), 'mystery-kind', gen_random_uuid(), 1)").bind("a", account).bind("h", hash)
                .then().block();

        lifecycle.prepare(account, request).block();
        Map<String, Object> blocked = erase(account, request.toString());
        assertThat(blocked.get("state")).isEqualTo("needs_review");
        assertThat(blocked.get("erased")).isEqualTo(false);
        assertThat(lifecycle.find(account).block().state()).isEqualTo("erasing");
        assertThat(count("SELECT count(*) FROM creation_canvas_agent_plan WHERE account_id = :a", account)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_studio_apply WHERE owner_account_id = :a", account)).isEqualTo(1);

        // 消除冲突（人工数据修复路径）后重跑 → completed。
        db.sql("DELETE FROM creation_canvas_agent_plan WHERE account_id = :a").bind("a", account).then().block();
        db.sql("DELETE FROM creation_studio_apply WHERE owner_account_id = :a").bind("a", account).then().block();
        Map<String, Object> done = erase(account, request.toString());
        assertThat(done.get("state")).isEqualTo("completed");
        assertThat(done.get("erased")).isEqualTo(true);
    }

    /**
     * TC104-01-04 幂等和崩溃续跑：201 行首批 200 后中断，续跑收敛；1001 行多批收敛；同 closureRequestId 重复调用 不重复副作用。
     */
    @Test
    void batchResumeIdempotencyAndThousandRows() {
        String account = "t10404-" + UUID.randomUUID();
        UUID request = UUID.randomUUID();
        String draft = UUID.randomUUID().toString();
        insertDraft(draft, account, null);
        for (int i = 0; i < 201; i++) {
            insertSourceDocument(account, draft, "req-b" + i);
        }
        lifecycle.prepare(account, request).block();
        var manifest = erasure.plan(account, request).block();
        // 逐步推进（每调用一批/一步），到 source_document 首批（200 行）后中断。
        for (int i = 0; i < 60; i++) {
            erasure.eraseNextBatch(manifest.id()).block();
            Long done = sourceStepDeleted(manifest.id());
            if (done != null && done >= 200) {
                break;
            }
        }
        var step = sourceStepDeleted(manifest.id());
        assertThat(step).isEqualTo(200L);
        assertThat(count("SELECT count(*) FROM creation_source_document WHERE owner_account_id = :a", account))
                .isEqualTo(1);
        // 续跑收敛 + 幂等重放。
        Map<String, Object> done = erase(account, request.toString());
        assertThat(done.get("erased")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) done.get("counts");
        assertThat(((Number) counts.get("source_document")).longValue()).isEqualTo(201L);
        Map<String, Object> replay = erase(account, request.toString());
        assertThat(replay.get("manifestId")).isEqualTo(done.get("manifestId"));
        assertThat(replay.get("erased")).isEqualTo(true);
        assertThat(count("SELECT count(*) FROM personal_data_erasure_manifest"
                + " WHERE closure_request_id = CAST(:a AS uuid)", request.toString())).isEqualTo(1);

        // 1001 行跨多批收敛（drain 单调用 256 批上限内）。
        String bulk = "t10404bulk-" + UUID.randomUUID();
        UUID bulkRequest = UUID.randomUUID();
        for (int i = 0; i < 1001; i++) {
            db.sql("INSERT INTO creation_draft(id, owner_account_id, title, source_type, created_at, updated_at)"
                    + " VALUES (gen_random_uuid(), :a, '千行草稿" + i + "', 'independent', now(), now())")
                    .bind("a", bulk).then().block();
        }
        lifecycle.prepare(bulk, bulkRequest).block();
        Map<String, Object> bulkDone = erase(bulk, bulkRequest.toString());
        assertThat(bulkDone.get("erased")).as("receipt=%s", bulkDone).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> bulkCounts = (Map<String, Object>) bulkDone.get("counts");
        assertThat(((Number) bulkCounts.get("creation_draft")).longValue()).isEqualTo(1001L);
    }

    /**
     * TC104-01-05 并发冻结边界：先写后冻结（lifecycle 行初始不存在）→ 行被清理；先冻结后写 → 屏障拒绝； 真并发交错 →
     * 无论先后，最终不穿透（清理后为 0）。
     */
    @Test
    void concurrentFreezeAndWriteInterleaving() {
        // 先写后冻结：触发器自动补 active 行，清理看到该行。
        String early = "t10405a-" + UUID.randomUUID();
        UUID earlyRequest = UUID.randomUUID();
        insertDraft(UUID.randomUUID().toString(), early, null);
        lifecycle.prepare(early, earlyRequest).block();
        Map<String, Object> earlyDone = erase(early, earlyRequest.toString());
        assertThat(earlyDone.get("erased")).isEqualTo(true);
        assertThat(count("SELECT count(*) FROM creation_draft WHERE owner_account_id = :a", early)).isZero();

        // 先冻结后写：个人写被屏障拒绝（SQLSTATE check_violation → account_closure_barrier）。
        String late = "t10405b-" + UUID.randomUUID();
        UUID lateRequest = UUID.randomUUID();
        lifecycle.prepare(late, lateRequest).block();
        assertThatThrownBy(() -> insertDraft(UUID.randomUUID().toString(), late, null))
                .hasMessageContaining("account_closure_barrier");
        assertThat(count("SELECT count(*) FROM creation_draft WHERE owner_account_id = :a", late)).isZero();

        // 真并发（无 deterministic 顺序）：写入与冻结竞争，最终清理后为 0，不穿透。
        String race = "t10405c-" + UUID.randomUUID();
        UUID raceRequest = UUID.randomUUID();
        Mono.when(insertDraftMono(UUID.randomUUID().toString(), race).onErrorResume((e) -> Mono.empty()),
                lifecycle.prepare(race, raceRequest)).block();
        Map<String, Object> raceDone = erase(race, raceRequest.toString());
        assertThat(raceDone.get("erased")).as("receipt=%s", raceDone).isEqualTo(true);
        assertThat(count("SELECT count(*) FROM creation_draft WHERE owner_account_id = :a", race)).isZero();
    }

    /** TC104-01-06 空/归属/审计：空白/无 gate 账号非法请求被拒；对照账号与经济键不变（空清理不外溢）。 */
    @Test
    void blankAccountAndForeignDataBoundaries() {
        String account = "t10406-" + UUID.randomUUID();
        String other = "t10406c-" + UUID.randomUUID();
        UUID request = UUID.randomUUID();
        String hash = "h".repeat(64);
        String otherDraft = UUID.randomUUID().toString();
        insertDraft(otherDraft, other, null);
        insertSourceDocument(other, otherDraft, "req-f1");
        db.sql("INSERT INTO ai_run(operation_id, account_id, capability, provider, budget_cents, status,"
                + " failure_reason, started_at) VALUES (gen_random_uuid(), :a, 'text', 'sandbox', 500,"
                + " 'failed', '他人错误文本', now())").bind("a", other).then().block();

        // 非法账号（空白）无 gate → 服务层拒绝。
        assertThatThrownBy(() -> erasure.plan("  ", UUID.randomUUID()).block())
                .isInstanceOf(PersonalDataErasureService.NoRetentionTaskException.class);

        lifecycle.prepare(account, request).block();
        Map<String, Object> receipt = erase(account, request.toString());
        assertThat(receipt.get("erased")).isEqualTo(true);
        // 对照账号数据与审计字段原样。
        assertThat(count("SELECT count(*) FROM creation_draft WHERE owner_account_id = :a", other)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM creation_source_document WHERE owner_account_id = :a", other))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ai_run WHERE account_id = :a AND budget_cents = 500"
                + " AND failure_reason = '他人错误文本'", other)).isEqualTo(1);
    }

    private Map<String, Object> erase(String account, String closureRequestId) {
        byte[] body = client().post().uri("/internal/compliance/accounts/" + account + "/erase")
                .header("X-Grassland-Identity", identityServiceAssertion()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("closureRequestId", closureRequestId)).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.success").isEqualTo(true).returnResult().getResponseBody();
        try {
            Map<String, Object> envelope = new com.fasterxml.jackson.databind.ObjectMapper().readValue(body,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) envelope.get("data");
            return data;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Long count(String sql, String arg) {
        return db.sql(sql).bind("a", arg).map((r) -> r.get("count", Long.class)).one().block();
    }

    private Long countRaw(String sql) {
        return db.sql(sql).map((r) -> r.get("count", Long.class)).one().block();
    }

    private Long sourceStepDeleted(UUID manifestId) {
        return db.sql("SELECT deleted_count FROM personal_data_erasure_step"
                + " WHERE manifest_id = CAST(:m AS uuid) AND resource_kind = 'source_document'")
                .bind("m", manifestId.toString()).map((r) -> r.get("deleted_count", Long.class)).one().block();
    }

    // ---------- 造数助手（合成 ID，路径参数一律绑定） ----------

    private void insertDraft(String id, String owner, String org) {
        var spec = db.sql("INSERT INTO creation_draft(id, owner_account_id, organization_id, title, source_type,"
                + " created_at, updated_at) VALUES (CAST(:d AS uuid), :a, :org, '草稿', 'independent', now(), now())")
                .bind("d", id).bind("a", owner);
        spec = org == null ? spec.bindNull("org", String.class) : spec.bind("org", org);
        spec.then().block();
    }

    private Mono<Void> insertDraftMono(String id, String owner) {
        return db.sql("INSERT INTO creation_draft(id, owner_account_id, title, source_type, created_at, updated_at)"
                + " VALUES (CAST(:d AS uuid), :a, '并发草稿', 'independent', now(), now())").bind("d", id)
                .bind("a", owner).then();
    }

    private String insertVisualPlan(String owner, String draftId, String requestId) {
        String plan = UUID.randomUUID().toString();
        String hash = "h".repeat(64);
        db.sql("INSERT INTO creation_visual_plan(id, owner_account_id, draft_id, request_id, request_hash,"
                + " source_document_id, source_content_hash, base_draft_version, base_content_hash, recipe_id,"
                + " recipe_version, upstream_commit, status) VALUES (CAST(:p AS uuid), :a, CAST(:d AS uuid), :r,"
                + " CAST(:h AS char(64)), gen_random_uuid(), CAST(:h AS char(64)), 1, CAST(:h AS char(64)),"
                + " 'card', 'v1', 'c1', 'ready')").bind("p", plan).bind("a", owner).bind("d", draftId)
                .bind("r", requestId).bind("h", hash).then().block();
        return plan;
    }

    private void insertPlanRevision(String planId) {
        db.sql("INSERT INTO creation_visual_plan_revision(plan_id, revision, document_json, document_hash)"
                + " VALUES (CAST(:p AS uuid), 1, '{}'::jsonb, CAST(:h AS char(64)))").bind("p", planId)
                .bind("h", "h".repeat(64)).then().block();
    }

    private void insertVisualQuote(String owner, String planId, String requestId) {
        db.sql("INSERT INTO creation_visual_quote(id, owner_account_id, plan_id, plan_revision, request_id,"
                + " request_hash, quote_json, expires_at) VALUES (gen_random_uuid(), :a, CAST(:p AS uuid), 1, :r,"
                + " CAST(:h AS char(64)), '{}'::jsonb, now() + interval '1 day')").bind("a", owner)
                .bind("p", planId).bind("r", requestId).bind("h", "h".repeat(64)).then().block();
    }

    private void insertSourceDocument(String owner, String draftId, String requestId) {
        db.sql("INSERT INTO creation_source_document(id, owner_account_id, draft_id, request_id, request_hash,"
                + " kind, raw_text, normalized_markdown, content_hash) VALUES (gen_random_uuid(), :a,"
                + " CAST(:d AS uuid), :r, CAST(:h AS char(64)), 'plain-text', '原文', '规范化',"
                + " CAST(:h AS char(64)))").bind("a", owner).bind("d", draftId).bind("r", requestId)
                .bind("h", "h".repeat(64)).then().block();
    }

    private void insertTextProposal(String owner, String draftId, String requestId) {
        db.sql("INSERT INTO creation_text_proposal(id, owner_account_id, draft_id, request_id, request_hash,"
                + " action, status, base_draft_version, base_content_hash, expires_at)"
                + " VALUES (gen_random_uuid(), :a, CAST(:d AS uuid), :r, CAST(:h AS char(64)), 'adapt-body',"
                + " 'applied', 1, CAST(:h AS char(64)), now() + interval '1 day')").bind("a", owner)
                .bind("d", draftId).bind("r", requestId).bind("h", "h".repeat(64)).then().block();
    }

    private String insertExport(String owner, String draftId, String requestId) {
        String export = UUID.randomUUID().toString();
        db.sql("INSERT INTO creation_export(id, owner_account_id, request_id, draft_id, version, format,"
                + " payload_hash, state) VALUES (CAST(:e AS uuid), :a, :r, CAST(:d AS uuid), 1, 'bundle-zip',"
                + " CAST(:h AS char(64)), 'ready')").bind("e", export).bind("a", owner).bind("d", draftId)
                .bind("r", requestId).bind("h", "h".repeat(64)).then().block();
        return export;
    }

    private void insertStudioApply(String owner, String kind, String resourceId, String requestId) {
        db.sql("INSERT INTO creation_studio_apply(id, owner_account_id, request_id, request_hash, kind,"
                + " resource_id, applied_draft_version) VALUES (gen_random_uuid(), :a, :r,"
                + " CAST(:h AS char(64)), :k, CAST(:res AS uuid), 1)").bind("a", owner).bind("r", requestId)
                .bind("h", "h".repeat(64)).bind("k", kind).bind("res", resourceId).then().block();
    }

    private void insertCanvasDocument(String account, String draftId) {
        db.sql("INSERT INTO creation_canvas_document(id, draft_id, account_id, document)"
                + " VALUES (gen_random_uuid(), CAST(:d AS uuid), :a, '{}'::jsonb)").bind("d", draftId)
                .bind("a", account).then().block();
    }

    private String insertWechatSync(String owner, String draftId, String exportId, String requestId) {
        String sync = UUID.randomUUID().toString();
        db.sql("INSERT INTO creation_wechat_draft_sync(id, owner_account_id, request_id, account_id,"
                + " account_version, draft_id, draft_version, export_id, payload_hash, payload_json, state,"
                + " dispatch_state) VALUES (CAST(:s AS uuid), :a, :r, gen_random_uuid(), 1, CAST(:d AS uuid), 1,"
                + " CAST(:e AS uuid), CAST(:h AS char(64)), '{}'::jsonb, 'succeeded', 'completed')")
                .bind("s", sync).bind("a", owner).bind("r", requestId).bind("d", draftId).bind("e", exportId)
                .bind("h", "h".repeat(64)).then().block();
        return sync;
    }

    private void insertWechatMapping(String owner, String syncId, String derivedKey) {
        db.sql("INSERT INTO creation_wechat_media_mapping(id, sync_id, owner_account_id, account_id,"
                + " account_version, media_ref_id, purpose, content_hash, derived_object_key, state)"
                + " VALUES (gen_random_uuid(), CAST(:s AS uuid), :a, gen_random_uuid(), 1, gen_random_uuid(),"
                + " 'content', CAST(:h AS char(64)), :k, 'succeeded')").bind("s", syncId).bind("a", owner)
                .bind("h", "h".repeat(64)).bind("k", derivedKey).then().block();
    }

    private void insertWorkspace(String storyboardId, String draftId, String account) {
        db.sql("INSERT INTO video_storyboard_workspace(storyboard_id, draft_id, account_id, operation_id,"
                + " request_hash) VALUES (CAST(:s AS uuid), CAST(:d AS uuid), :a, gen_random_uuid(),"
                + " CAST(:h AS char(64)))").bind("s", storyboardId).bind("d", draftId).bind("a", account)
                .bind("h", "h".repeat(64)).then().block();
    }

    private void insertVariant(String storyboardId, String account) {
        db.sql("INSERT INTO video_storyboard_variant(storyboard_id, parent_storyboard_id, root_storyboard_id,"
                + " account_id, operation_id, request_hash, source_edit_version, source_draft_version, title,"
                + " shot_id_map) VALUES (gen_random_uuid(), CAST(:s AS uuid), CAST(:s AS uuid), :a,"
                + " gen_random_uuid(), CAST(:h AS char(64)), 1, 1, '变体', '{}'::jsonb)").bind("s", storyboardId)
                .bind("a", account).bind("h", "h".repeat(64)).then().block();
    }

    private void insertAgentPlan(String account, String draftId, String storyboardId) {
        db.sql("INSERT INTO creation_canvas_agent_plan(id, account_id, operation_id, request_hash, draft_id,"
                + " storyboard_id, base_draft_version, base_edit_version, base_canvas_revision, status,"
                + " selected_node_ids, instruction, expires_at) VALUES (gen_random_uuid(), :a, gen_random_uuid(),"
                + " CAST(:h AS char(64)), CAST(:d AS uuid), CAST(:s AS uuid), 1, 1, 1, 'ready', '[]'::jsonb,"
                + " '优化', now() + interval '1 day')").bind("a", account).bind("h", "h".repeat(64))
                .bind("d", draftId).bind("s", storyboardId).then().block();
    }

    private String insertCardOperation(String owner, String draftId, String planId, String requestId) {
        String operation = UUID.randomUUID().toString();
        var spec = db.sql("INSERT INTO card_series_operation(id, owner_account_id, request_id, request_digest,"
                + " status, api_version, draft_id, plan_id) VALUES (CAST(:o AS uuid), :a, :r, 'digest',"
                + " 'succeeded', 2, CAST(:d AS uuid), CAST(:p AS uuid))").bind("o", operation).bind("a", owner)
                .bind("r", requestId);
        spec = draftId == null ? spec.bindNull("d", java.util.UUID.class)
                : spec.bind("d", java.util.UUID.fromString(draftId));
        spec = planId == null ? spec.bindNull("p", java.util.UUID.class)
                : spec.bind("p", java.util.UUID.fromString(planId));
        spec.then().block();
        return operation;
    }

    private void insertVisualItem(String operationId, String itemId) {
        db.sql("INSERT INTO creation_visual_item(id, operation_id, item_id, position, state,"
                + " execution_operation_id) VALUES (gen_random_uuid(), CAST(:o AS uuid), :i, 1, 'succeeded',"
                + " gen_random_uuid())").bind("o", operationId).bind("i", itemId).then().block();
    }

    private void insertVisualArtifact(String owner, String draftId, String planId) {
        db.sql("INSERT INTO creation_visual_artifact(id, owner_account_id, draft_id, plan_id, plan_revision,"
                + " item_id, attempt_id, original_media_id, delivery_media_id, target_aspect, width, height,"
                + " content_hash) VALUES (gen_random_uuid(), :a, CAST(:d AS uuid), CAST(:p AS uuid), 1, 'i1',"
                + " gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), '1:1', 100, 100, CAST(:h AS char(64)))")
                .bind("a", owner).bind("d", draftId).bind("p", planId).bind("h", "h".repeat(64)).then().block();
    }

    /** §7.4 全资源 fixture：本人个人各一行 + 组织/他人对照各一行（计费事实行不删只脱敏）。 */
    private void seedFullPersonalFixture(String account, String other, String org) {
        String hash = "h".repeat(64);
        // 草稿族（个人 + 组织 + 他人）。
        String draft = UUID.randomUUID().toString();
        String orgDraft = UUID.randomUUID().toString();
        String otherDraft = UUID.randomUUID().toString();
        insertDraft(draft, account, null);
        insertDraft(orgDraft, account, org);
        insertDraft(otherDraft, other, null);
        db.sql("INSERT INTO creation_draft_version(draft_id, version, title, source_type, status,"
                + " snapshotted_by) VALUES (CAST(:d AS uuid), 1, '版本一', 'independent', 'draft', :a)").bind("d", draft)
                .bind("a", account).then().block();
        insertSourceDocument(account, draft, "req-1");
        // 组织草稿下的来源（TC104-01-01 组织父链保留）。
        insertSourceDocument(account, orgDraft, "req-1-org");
        insertTextProposal(account, draft, "req-2");
        insertTextProposal(account, orgDraft, "req-2-org");
        String plan = insertVisualPlan(account, draft, "req-3");
        String orgPlan = insertVisualPlan(account, orgDraft, "req-3-org");
        insertPlanRevision(plan);
        insertPlanRevision(orgPlan);
        insertVisualQuote(account, plan, "req-4");
        insertVisualQuote(account, orgPlan, "req-4-org");
        insertStudioApply(account, "plan-patch", plan, "req-5");
        insertStudioApply(account, "plan-patch", orgPlan, "req-5-org");
        String operation = UUID.randomUUID().toString();
        db.sql("INSERT INTO card_series_operation(id, owner_account_id, request_id, request_digest, status)"
                + " VALUES (CAST(:o AS uuid), :a, 'req-6', 'digest-6', 'succeeded')").bind("o", operation)
                .bind("a", account).then().block();
        db.sql("INSERT INTO creation_visual_item(id, operation_id, item_id, position, state,"
                + " execution_operation_id) VALUES (gen_random_uuid(), CAST(:o AS uuid), 'item-1', 1, 'succeeded',"
                + " gen_random_uuid())").bind("o", operation).then().block();
        db.sql("INSERT INTO creation_visual_artifact(id, owner_account_id, draft_id, plan_id, plan_revision,"
                + " item_id, attempt_id, original_media_id, delivery_media_id, target_aspect, width, height,"
                + " content_hash) VALUES (gen_random_uuid(), :a, CAST(:d AS uuid), CAST(:p AS uuid), 1, 'item-1',"
                + " gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), '1:1', 100, 100, CAST(:h AS char(64)))")
                .bind("a", account).bind("d", draft).bind("p", plan).bind("h", hash).then().block();
        String personalExport = insertExport(account, draft, "req-7");
        String orgExport = insertExport(account, orgDraft, "req-7-org");
        // 公众号连接族（含派生对象 key）：个人与组织草稿 sync 各一条。
        String wechatAccount = UUID.randomUUID().toString();
        String wechatSync = UUID.randomUUID().toString();
        db.sql("INSERT INTO creation_wechat_account(id, owner_account_id, display_name, app_id, encrypted_secret)"
                + " VALUES (CAST(:w AS uuid), :a, '公众号', 'wx-app', 'cipher')").bind("w", wechatAccount)
                .bind("a", account).then().block();
        db.sql("INSERT INTO creation_wechat_draft_sync(id, owner_account_id, request_id, account_id,"
                + " account_version, draft_id, draft_version, export_id, payload_hash, payload_json, state,"
                + " dispatch_state) VALUES (CAST(:s AS uuid), :a, 'req-8', CAST(:w AS uuid), 1,"
                + " CAST(:d AS uuid), 1, gen_random_uuid(), CAST(:h AS char(64)), '{}'::jsonb, 'succeeded',"
                + " 'completed')").bind("s", wechatSync).bind("a", account).bind("w", wechatAccount).bind("d", draft)
                .bind("h", hash).then().block();
        db.sql("INSERT INTO creation_wechat_media_mapping(id, sync_id, owner_account_id, account_id,"
                + " account_version, media_ref_id, purpose, content_hash, derived_object_key, state)"
                + " VALUES (gen_random_uuid(), CAST(:s AS uuid), :a, CAST(:w AS uuid), 1, gen_random_uuid(),"
                + " 'content', CAST(:h AS char(64)), 'wechat/derived-1', 'succeeded')").bind("s", wechatSync)
                .bind("a", account).bind("w", wechatAccount).bind("h", hash).then().block();
        String orgWechatSync = insertWechatSync(account, orgDraft, orgExport, "req-8-org");
        insertWechatMapping(account, orgWechatSync, "wechat/org-derived");
        // 视频族（个人 storyboard 全链 + 组织 storyboard 对照）。
        String storyboard = seedStoryboard(account, null, hash);
        seedStoryboard(account, org, hash);
        insertWorkspace(storyboard, draft, account);
        insertVariant(storyboard, account);
        insertCanvasDocument(account, draft);
        insertCanvasDocument(account, orgDraft);
        insertAgentPlan(account, draft, storyboard);
        // 素材/转录/指纹/生成。
        seedContentAsset(account, "personal", null, hash);
        seedContentAsset(account, "merchant", org, hash);
        // 他人授予本账号的授权（注销时撤销）+ 本账号授予他人的授权（组织素材，保留）。
        db.sql("INSERT INTO content_asset_grant(asset_id, grant_type, grantee_account_id, granted_by,"
                + " lease_until) VALUES ((SELECT id FROM content_asset WHERE owner_account_id = :a"
                + " AND library_type = 'merchant' LIMIT 1), 'recommender_share', :a, :other,"
                + " now() + interval '30 days')").bind("a", account).bind("other", other).then().block();
        db.sql("INSERT INTO content_asset_grant(asset_id, grant_type, grantee_account_id, granted_by,"
                + " lease_until) VALUES ((SELECT id FROM content_asset WHERE owner_account_id = :a"
                + " AND library_type = 'merchant' LIMIT 1), 'recommender_share', :other, :a,"
                + " now() + interval '30 days')").bind("a", account).bind("other", other).then().block();
        db.sql("INSERT INTO speech_transcription(id, media_reference_id, owner_account_id, requested_language,"
                + " duration_ms, status, failure_code) VALUES (gen_random_uuid(), gen_random_uuid(), :a,"
                + " 'zh-CN', 1000, 'failed', 'upstream_timeout')").bind("a", account).then().block();
        db.sql("INSERT INTO content_fingerprint(owner_account_id, simhash, shingle_count, source_kind)"
                + " VALUES (:a, 1, 2, 'generation')").bind("a", account).then().block();
        db.sql("INSERT INTO creation_generation(owner_account_id, kind, mode, resolution, provider, prompt_text,"
                + " result) VALUES (:a, 'article', 'independent', 'platform', 'sandbox', '写一篇'," + " '{}'::jsonb)")
                .bind("a", account).then().block();
        db.sql("INSERT INTO creation_context_snapshot(account_id, task_id, application_id, task_version,"
                + " platform_id, content_form_id, task_snapshot, platform_rules_snapshot, material_snapshot,"
                + " ai_config_snapshot) VALUES (:a, 'task-1', 'app-1', 1, 'wechat', 'article', '{}'::jsonb,"
                + " '{}'::jsonb, '{}'::jsonb, '{}'::jsonb)").bind("a", account).then().block();
        db.sql("INSERT INTO intelligence_style_preferences(account_id) VALUES (:a)").bind("a", account).then().block();
        db.sql("INSERT INTO ai_provider_preference(account_id, capability) VALUES (:a, 'text')").bind("a", account)
                .then().block();
        db.sql("INSERT INTO ai_provider_key(owner_account_id, organization_id, capability, base_url,"
                + " encrypted_key, masked_hint) VALUES (:a, NULL, 'text', 'https://byok.example/v1', 'cipher',"
                + " 'sk-***')").bind("a", account).then().block();
        db.sql("INSERT INTO ai_provider_key(owner_account_id, organization_id, capability, base_url,"
                + " encrypted_key, masked_hint) VALUES (:a, :org, 'text', 'https://byok.example/v1', 'cipher',"
                + " 'sk-***')").bind("a", account).bind("org", org).then().block();
        // 媒体（个人两个对象：object_key + upload_key；组织 + 他人对照）。
        db.sql("INSERT INTO media_reference(owner_account_id, purpose, object_key, upload_key, mime_type)"
                + " VALUES (:a, 'user_upload', 'media/personal-1', 'upload/personal-1', 'image/png')")
                .bind("a", account).then().block();
        db.sql("INSERT INTO media_reference(owner_account_id, purpose, object_key, mime_type)"
                + " VALUES (:a, 'article_generated', 'media/personal-2', 'image/png')").bind("a", account).then()
                .block();
        db.sql("INSERT INTO media_reference(owner_account_id, organization_id, purpose, object_key, mime_type)"
                + " VALUES (:a, :org, 'engagement_attachment', 'media/org-1', 'image/png')").bind("a", account)
                .bind("org", org).then().block();
        db.sql("INSERT INTO media_reference(owner_account_id, purpose, object_key, mime_type)"
                + " VALUES (:a, 'user_upload', 'media/other-1', 'image/png')").bind("a", other).then().block();
        // 计费事实：ai_run（错误文本脱敏）+ video_generation_job（prompt 脱敏）。
        db.sql("INSERT INTO ai_run(operation_id, account_id, capability, provider, budget_cents, status,"
                + " failure_reason, started_at) VALUES (gen_random_uuid(), :a, 'text', 'sandbox', 100,"
                + " 'failed', '上游超时个人错误文本', now())").bind("a", account).then().block();
        db.sql("INSERT INTO video_generation_job(account_id, idempotency_key, provider, model,"
                + " requested_duration_seconds, aspect_ratio, pricing_version, unit_price_cents,"
                + " estimated_cost_cents, platform_model_version, input_payload, status)"
                + " VALUES (:a, 'idem-1', 'sandbox', 'video-1', 15, '16:9', 'v1', 100, 100, 1,"
                + " '{\"prompt\":\"个人提示词\"}'::jsonb, 'failed')").bind("a", account).then().block();
    }

    private String seedStoryboard(String account, String org, String hash) {
        String storyboard = UUID.randomUUID().toString();
        if (org == null) {
            db.sql("INSERT INTO video_storyboard(id, account_id, target_duration_seconds, request_payload)"
                    + " VALUES (CAST(:s AS uuid), :a, 15, '{}'::jsonb)").bind("s", storyboard).bind("a", account).then()
                    .block();
        } else {
            db.sql("INSERT INTO video_storyboard(id, account_id, organization_id, target_duration_seconds,"
                    + " request_payload) VALUES (CAST(:s AS uuid), :a, :org, 15, '{}'::jsonb)").bind("s", storyboard)
                    .bind("a", account).bind("org", org).then().block();
        }
        String shot = UUID.randomUUID().toString();
        db.sql("INSERT INTO video_shot(id, storyboard_id, seq, visual, narration, planned_seconds, camera_move,"
                + " prompt) VALUES (CAST(:sh AS uuid), CAST(:s AS uuid), 1, '画面', '旁白', 5, 'static'," + " '个人提示词')")
                .bind("sh", shot).bind("s", storyboard).then().block();
        db.sql("INSERT INTO video_shot_media_source(shot_id, storyboard_id, source_kind, audio_mode)"
                + " VALUES (CAST(:sh AS uuid), CAST(:s AS uuid), 'generated', 'narration')").bind("sh", shot)
                .bind("s", storyboard).then().block();
        db.sql("INSERT INTO video_shot_take(shot_id, take_no, provider, model, status) VALUES"
                + " (CAST(:sh AS uuid), 1, 'sandbox', 'video-1', 'succeeded')").bind("sh", shot).then().block();
        db.sql("INSERT INTO video_shot_audio(shot_id, status) VALUES (CAST(:sh AS uuid), 'succeeded')").bind("sh", shot)
                .then().block();
        db.sql("INSERT INTO video_production_task(storyboard_id, account_id, operation_id,"
                + " target_duration_seconds, pricing_version, unit_price_cents, estimated_cost_cents, phase,"
                + " completed_at) VALUES (CAST(:s AS uuid), :a, 'op-" + UUID.randomUUID() + "', 15, 'v1', 100,"
                + " 100, 'succeeded', now())").bind("s", storyboard).bind("a", account).then().block();
        return storyboard;
    }

    private void seedContentAsset(String account, String libraryType, String org, String hash) {
        String asset = UUID.randomUUID().toString();
        var insert = db.sql("INSERT INTO content_asset(id, media_reference_id, library_type, category,"
                + " owner_account_id, organization_id, title) VALUES (CAST(:c AS uuid), gen_random_uuid(), :lib,"
                + " 'other', :a, :org, '素材')").bind("c", asset).bind("lib", libraryType).bind("a", account);
        insert = org == null ? insert.bindNull("org", String.class) : insert.bind("org", org);
        insert.then().block();
        db.sql("INSERT INTO content_asset_version(asset_id, version, library_type, category, owner_account_id,"
                + " title, snapshotted_by) VALUES (CAST(:c AS uuid), 1, :lib, 'other', :a, '素材', :a)").bind("c", asset)
                .bind("lib", libraryType).bind("a", account).then().block();
        db.sql("INSERT INTO content_asset_embedding(asset_id, asset_version, content_hash, status,"
                + " failure_code) VALUES (CAST(:c AS uuid), 1, :h, 'failed', 'indexer_unavailable')").bind("c", asset)
                .bind("h", hash).then().block();
    }
}
