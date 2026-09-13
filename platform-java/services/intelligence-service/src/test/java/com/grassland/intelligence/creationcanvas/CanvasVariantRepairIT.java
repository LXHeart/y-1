package com.grassland.intelligence.creationcanvas;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.creationassistant.CreationDraftRepository;
import com.grassland.intelligence.videoproduction.VideoStoryboardVariantService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"ai.video-generation.worker-enabled=false"})
class CanvasVariantRepairIT extends IntelligenceItSupport {
	private static final String ACCOUNT = "10205000-0000-4000-8000-000000000001";
	@Autowired
	VideoStoryboardVariantService variants;
	@Autowired
	CreationDraftRepository drafts;
	private record Fixture(UUID parent, UUID parentDraft, UUID child, UUID childDraft, UUID shot) {
	}

	@Test
	void repairUsesSourceVersionKeepsUserLayoutAndSnapshotsExactlyOnce() {
		var f = fixture();
		corrupt(f);
		var original = drafts.findById(f.parentDraft()).block();
		drafts.appendVersion(original, ACCOUNT).block();
		db.sql("UPDATE creation_draft SET version=2, store_id='new-store', title='parent changed' WHERE id=:id")
				.bind("id", f.parentDraft()).then().block();
		// Before explicit repair, content writes cannot bypass the known bad
		// provenance.
		client().put().uri("/api/video-production/shots/{id}/content", f.shot())
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("visual", "must not write")).exchange().expectStatus().isEqualTo(409);
		String operation = UUID.randomUUID().toString();
		bind(f, operation, 200);
		var repaired = drafts.findById(f.childDraft()).block();
		assertThat(repaired.sourceType().db()).isEqualTo("store");
		assertThat(repaired.storeId()).isEqualTo("original-store");
		assertThat(repaired.version()).isEqualTo(2);
		assertThat(drafts.findVersion(f.childDraft(), 1).block().sourceType().db()).isEqualTo("independent");
		assertThat(canvas(f)).contains(f.child().toString()).contains("user note").contains("777");
		assertThat(revision(f)).isEqualTo(2);
		bind(f, operation, 200);
		assertThat(drafts.findById(f.childDraft()).block().version()).isEqualTo(2);
		assertThat(revision(f)).isEqualTo(2);
		assertThat(historyCount(f)).isEqualTo(1);
	}

	@Test
	void missingSourceVersionRefusesGuessingAndDoesNotChangeData() {
		var f = fixture();
		corrupt(f);
		String before = canvas(f);
		db.sql("UPDATE video_storyboard_variant SET source_draft_version=999 WHERE storyboard_id=:id")
				.bind("id", f.child()).then().block();
		bind(f, UUID.randomUUID().toString(), 409);
		assertThat(canvas(f)).isEqualTo(before);
		assertThat(drafts.findById(f.childDraft()).block().version()).isEqualTo(1);
		assertThat(historyCount(f)).isZero();
	}

	@Test
	void taskSourceIsRecoveredFromOwnedFrozenSnapshotAndOriginalStoreVersion() {
		var f = fixture();
		UUID snapshot = db.sql(
				"INSERT INTO creation_context_snapshot(account_id,task_id,application_id,task_version,platform_id,content_form_id,"
						+ "task_snapshot,platform_rules_snapshot,material_snapshot,ai_config_snapshot) VALUES (:account,'task-102',:app,7,'douyin','video',"
						+ "'{}'::jsonb,'{}'::jsonb,'{}'::jsonb,'{}'::jsonb) RETURNING id")
				.bind("account", ACCOUNT).bind("app", UUID.randomUUID().toString()).map(row -> row.get(0, UUID.class))
				.one().block();
		db.sql("UPDATE video_storyboard SET context_snapshot_id=:snapshot WHERE id IN (:parent,:child)")
				.bind("snapshot", snapshot).bind("parent", f.parent()).bind("child", f.child()).then().block();
		db.sql("UPDATE creation_draft SET source_type='task',task_id='task-102',task_version=7,platform='douyin',content_form='video' WHERE id=:id")
				.bind("id", f.parentDraft()).then().block();
		corrupt(f);
		bind(f, UUID.randomUUID().toString(), 200);
		var repaired = drafts.findById(f.childDraft()).block();
		assertThat(repaired.sourceType().db()).isEqualTo("task");
		assertThat(repaired.taskId()).isEqualTo("task-102");
		assertThat(repaired.taskVersion()).isEqualTo(7);
		assertThat(repaired.platform()).isEqualTo("douyin");
		assertThat(repaired.contentForm()).isEqualTo("video");
		assertThat(repaired.storeId()).isEqualTo("original-store");
		assertThat(historyCount(f)).isEqualTo(1);
	}

	@Test
	void archivedAndCorrectProjectsAreNeverRewritten() {
		var f = fixture();
		bind(f, UUID.randomUUID().toString(), 200);
		assertThat(revision(f)).isEqualTo(1);
		assertThat(historyCount(f)).isZero();
		corrupt(f);
		client().post().uri("/api/creation-drafts/{id}/archive", f.childDraft())
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).exchange().expectStatus().isOk();
		String before = canvas(f);
		bind(f, UUID.randomUUID().toString(), 200);
		assertThat(canvas(f)).isEqualTo(before);
		assertThat(drafts.findById(f.childDraft()).block().sourceType().db()).isEqualTo("independent");
		assertThat(drafts.findById(f.childDraft()).block().version()).isEqualTo(2);
	}

	@Test
	void failureAfterSourceRepairRollsBackBothHistoryAndCanvasThenCanRetry() {
		var f = fixture();
		corrupt(f);
		db.sql("CREATE FUNCTION task102_fail_canvas_repair() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
				+ "RAISE EXCEPTION 'task102 injected repair failure'; END $$").then().block();
		db.sql("CREATE TRIGGER task102_fail_canvas_repair BEFORE UPDATE ON creation_canvas_document "
				+ "FOR EACH ROW EXECUTE FUNCTION task102_fail_canvas_repair()").then().block();
		String operation = UUID.randomUUID().toString();
		try {
			bind(f, operation, 500);
		} finally {
			db.sql("DROP TRIGGER task102_fail_canvas_repair ON creation_canvas_document").then().block();
			db.sql("DROP FUNCTION task102_fail_canvas_repair()").then().block();
		}
		assertThat(drafts.findById(f.childDraft()).block().version()).isEqualTo(1);
		assertThat(historyCount(f)).isZero();
		assertThat(revision(f)).isEqualTo(1);
		bind(f, operation, 200);
		assertThat(drafts.findById(f.childDraft()).block().version()).isEqualTo(2);
		assertThat(revision(f)).isEqualTo(2);
	}

	private Fixture fixture() {
		UUID parent = db
				.sql("INSERT INTO video_storyboard(account_id,target_duration_seconds,request_payload) "
						+ "VALUES (:account,15,'{}'::jsonb) RETURNING id")
				.bind("account", ACCOUNT).map(row -> row.get(0, UUID.class)).one().block();
		UUID shot = db.sql(
				"INSERT INTO video_shot(storyboard_id,seq,visual,narration,planned_seconds,camera_move,anchor_image_index,prompt) "
						+ "VALUES (:sb,1,'original','n',5,'固定机位',0,'original') RETURNING id")
				.bind("sb", parent).map(row -> row.get(0, UUID.class)).one().block();
		UUID parentDraft = db.sql(
				"INSERT INTO creation_draft(owner_account_id,title,source_type,store_id,status,version,workspace_json) "
						+ "VALUES (:account,'source','store','original-store','draft',1,'{\"schemaVersion\":1,\"capability\":\"video\"}'::jsonb) RETURNING id")
				.bind("account", ACCOUNT).map(row -> row.get(0, UUID.class)).one().block();
		db.sql("INSERT INTO video_storyboard_workspace(storyboard_id,draft_id,account_id,operation_id,request_hash) "
				+ "VALUES (:sb,:draft,:account,gen_random_uuid(),'fixture')").bind("sb", parent)
				.bind("draft", parentDraft).bind("account", ACCOUNT).then().block();
		String document = "{\"schemaVersion\":1,\"storyboardId\":\"" + parent
				+ "\",\"viewport\":{\"panX\":0,\"panY\":0,\"scale\":1},"
				+ "\"nodes\":[{\"id\":\"note:10205000-0000-4000-8000-000000000099\",\"kind\":\"note\",\"refType\":\"note\",\"refId\":null,\"label\":null,\"text\":\"user note\",\"x\":777,\"y\":100},"
				+ "{\"id\":\"shot:" + shot + "\",\"kind\":\"shot\",\"refType\":\"shot\",\"refId\":\"" + shot
				+ "\",\"label\":null,\"text\":null,\"x\":40,\"y\":40}],"
				+ "\"edges\":[{\"id\":\"edge-1\",\"kind\":\"reference\",\"fromNodeId\":\"note:10205000-0000-4000-8000-000000000099\",\"toNodeId\":\"shot:"
				+ shot + "\"}],\"activeBranchId\":null}";
		db.sql("INSERT INTO creation_canvas_document(id,draft_id,account_id,schema_version,revision,document) VALUES (gen_random_uuid(),:draft,:account,1,1,CAST(:document AS jsonb))")
				.bind("draft", parentDraft).bind("account", ACCOUNT).bind("document", document).then().block();
		var result = variants
				.derive(ACCOUNT, parent, new VideoStoryboardVariantService.CreateVariantRequest(UUID.randomUUID(), 1L,
						1L, "child", List.of(shot)))
				.block();
		return new Fixture(parent, parentDraft, UUID.fromString((String) result.variant().get("storyboardId")),
				UUID.fromString((String) result.project().get("id")),
				UUID.fromString(result.shotIdMap().get(shot.toString())));
	}

	private void corrupt(Fixture f) {
		db.sql("UPDATE creation_draft SET source_type='independent', store_id=NULL WHERE id=:id")
				.bind("id", f.childDraft()).then().block();
		db.sql("UPDATE creation_canvas_document SET document=jsonb_set(document,'{storyboardId}',to_jsonb(CAST(:parent AS text))) WHERE draft_id=:draft")
				.bind("parent", f.parent().toString()).bind("draft", f.childDraft()).then().block();
	}
	private void bind(Fixture f, String operation, int status) {
		client().post().uri("/api/video-production/storyboards/{id}/workspace", f.child())
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("operationId", operation)).exchange().expectStatus().isEqualTo(status);
	}
	private String canvas(Fixture f) {
		return db.sql("SELECT document::text FROM creation_canvas_document WHERE draft_id=:id")
				.bind("id", f.childDraft()).map(row -> row.get(0, String.class)).one().block();
	}
	private long revision(Fixture f) {
		return db.sql("SELECT revision FROM creation_canvas_document WHERE draft_id=:id").bind("id", f.childDraft())
				.map(row -> row.get(0, Long.class)).one().block();
	}
	private long historyCount(Fixture f) {
		return db.sql("SELECT count(*) FROM creation_draft_version WHERE draft_id=:id").bind("id", f.childDraft())
				.map(row -> row.get(0, Long.class)).one().block();
	}
}
