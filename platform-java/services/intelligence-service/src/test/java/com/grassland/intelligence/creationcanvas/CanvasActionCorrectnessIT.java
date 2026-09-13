package com.grassland.intelligence.creationcanvas;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.videoproduction.*;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@TestPropertySource(properties = {"ai.video-generation.worker-enabled=false"})
class CanvasActionCorrectnessIT extends CanvasActionFixtureSupport {
	@MockitoBean
	VideoGenerationProviderResolver provider;
	@Autowired
	TakeGenerationWorker worker;
	@Autowired
	VideoShotTakeRepository takes;
	@Autowired
	CanvasAgentPlanRepository plans;
	@Autowired
	VideoStoryboardEditService edits;
	@Autowired
	VideoStoryboardVariantService variants;
	@Autowired
	CanvasProjectAccess projects;
	@Autowired
	VideoProductionTaskService taskService;
	@Autowired
	TransactionalOperator transactions;

	@Test
	void initialNullAndVariantUseRealVersionsAndReplayTheirExactResult() {
		var f = fixture();
		UUID initial = ready(f, "{\"kind\":\"prepare-generation\",\"mode\":\"initial\",\"shotId\":null}");
		var prepared = apply(initial, 200);
		assertThat(prepared.path("preparedGeneration").path("shotId").isNull()).isTrue();
		assertThat(count("video_production_task", "storyboard_id", f.storyboard())).isZero();
		assertThat(apply(initial, 200)).isEqualTo(prepared);
		UUID variantPlan = ready(f, variant(f));
		var applied = apply(variantPlan, 200);
		assertThat(apply(variantPlan, 200)).isEqualTo(applied);
		var derived = applied.path("variant");
		assertThat(derived.path("draftId").asText()).isNotEqualTo(f.draft().toString());
		assertThat(derived.path("sourceEditVersion").asLong()).isEqualTo(1);
		verifyNoInteractions(provider);
	}

	@Test
	void editsAndAppendsSynchronizePromptAndReturnAllNewIdsButSameValuesDoNotRotateVersion() {
		var f = fixture();
		var edited = apply(ready(f, edit(f, "{\"visual\":\"AI new visual\"}", true)), 200);
		assertThat(edited.path("editVersion").asLong()).isEqualTo(2);
		assertThat(edited.path("affectedShotIds").size()).isEqualTo(2);
		String added = edited.path("affectedShotIds").get(1).asText();
		assertThat(column("video_shot", "prompt", f.shot())).isEqualTo("AI new visual");
		assertThat(column("video_shot", "prompt", UUID.fromString(added))).isEqualTo("appended visual");
		assertThat(column("video_shot", "seq", UUID.fromString(added))).isEqualTo("3");
		var noChange = apply(ready(f, edit(f, "{\"visual\":\"AI new visual\"}", false)), 200);
		assertThat(noChange.path("editVersion").asLong()).isEqualTo(2);
		apply(ready(f, edit(f, "{\"narration\":\"only narration\"}", false)), 200);
		assertThat(column("video_shot", "prompt", f.shot())).isEqualTo("AI new visual");
		verifyNoInteractions(provider);

		// The actual worker reads the saved prompt; only the provider port is a
		// fixture.
		task(f, "generating");
		var adapter = mock(VideoGenerationProvider.class);
		when(adapter.submit(any())).thenReturn(Mono.just(new VideoGenerationProvider.ProviderResult(
				VideoGenerationProvider.ProviderResult.State.QUEUED, "fixture-handle", 0, null, null, null, null)));
		var resolution = ProviderResolution.platform(UUID.randomUUID(), "sandbox", "http://localhost",
				"sandbox-video-v1", 1, 1, null, null);
		when(provider.resolveVideoGeneration())
				.thenReturn(Mono.just(VideoGenerationProviderResolver.VideoProviderResolution
						.of(new VideoGenerationProviderResolver.VideoProviderResolution.Plan(adapter, resolution, 1,
								"v1"))));
		var take = takes.create(f.shot(), 1, "sandbox", "sandbox-video-v1").block();
		worker.process(take).block();
		var command = org.mockito.ArgumentCaptor.forClass(VideoGenerationProvider.ProviderCommand.class);
		verify(adapter).submit(command.capture());
		assertThat(command.getValue().prompt()).isEqualTo("AI new visual");
	}

	@Test
	void regenerateAndRerollRespectExistingStagesAndRejectOwnMediaWithoutSideEffects() {
		var f = fixture();
		apply(ready(f, prepare(f, "regenerate")), 409);
		UUID task = task(f, "generating");
		apply(ready(f, prepare(f, "regenerate")), 200);
		apply(ready(f, prepare(f, "reroll")), 409);
		db.sql("UPDATE video_production_task SET phase='succeeded' WHERE id=:id").bind("id", task).then().block();
		apply(ready(f, prepare(f, "reroll")), 200);
		apply(ready(f, prepare(f, "regenerate")), 409);
		UUID media = db.sql(
				"INSERT INTO media_reference(owner_account_id,purpose,object_key,mime_type,status) VALUES (:a,'video_asset',:key,'video/mp4','active') RETURNING id")
				.bind("a", ACCOUNT).bind("key", UUID.randomUUID().toString()).map(r -> r.get(0, UUID.class)).one()
				.block();
		db.sql("INSERT INTO video_shot_media_source(shot_id,storyboard_id,source_kind,media_id,trim_start_ms,trim_end_ms,audio_mode) VALUES (:shot,:sb,'own-media',:media,0,5000,'mute')")
				.bind("shot", f.shot()).bind("sb", f.storyboard()).bind("media", media).then().block();
		apply(ready(f, prepare(f, "reroll")), 409);
		assertThat(count("video_shot_take", "shot_id", f.shot())).isZero();
		assertThat(column("video_production_task", "recompose_seq", task)).isEqualTo("0");
		assertThat(column("video_storyboard", "edit_version", f.storyboard())).isEqualTo("1");
		verifyNoInteractions(provider);
	}

	@Test
	void expiryEqualityArchiveCommittedAndDeletedParentsAreEnforced() {
		var f = fixture();
		UUID id = ready(f, edit(f, "{\"visual\":\"changed\"}", false));
		var row = plans.findById(id).block();
		var service = new CanvasAgentActionService(plans, db, edits, variants, projects, taskService, transactions,
				Clock.fixed(row.expiresAt().toInstant(), ZoneOffset.UTC));
		assertThatThrownBy(() -> service.apply(ACCOUNT, id).block()).hasMessageContaining("过期");
		db.sql("UPDATE video_storyboard SET status='committed' WHERE id=:id").bind("id", f.storyboard()).then().block();
		apply(id, 409);
		client().post().uri("/api/creation-drafts/{id}/archive", f.draft())
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).exchange().expectStatus().isOk();
		apply(ready(f, variant(f)), 409);
		var other = fixture();
		UUID replay = ready(other, edit(other, "{\"visual\":\"changed\"}", false));
		apply(replay, 200);
		client().delete().uri("/api/creation-drafts/{id}", other.draft())
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).exchange().expectStatus().isOk();
		apply(replay, 404);
	}
}

/**
 * Synthetic project seeding is kept separate from the public HTTP actions being
 * verified.
 */
abstract class CanvasActionFixtureSupport extends IntelligenceItSupport {
	static final String ACCOUNT = "10208000-0000-4000-8000-000000000001";
	static final ObjectMapper JSON = new ObjectMapper();
	record Fixture(UUID draft, UUID storyboard, UUID shot, UUID second) {
	}

	Fixture fixture() {
		UUID board = db.sql(
				"INSERT INTO video_storyboard(account_id,target_duration_seconds,request_payload) VALUES (:a,15,'{}'::jsonb) RETURNING id")
				.bind("a", ACCOUNT).map(r -> r.get(0, UUID.class)).one().block();
		List<UUID> shots = new ArrayList<>();
		for (int i = 1; i <= 2; i++)
			shots.add(db.sql(
					"INSERT INTO video_shot(storyboard_id,seq,visual,narration,planned_seconds,camera_move,anchor_image_index,prompt) "
							+ "VALUES (:sb,:seq,'original','n',5,'固定机位',0,'old prompt') RETURNING id")
					.bind("sb", board).bind("seq", i).map(r -> r.get(0, UUID.class)).one().block());
		UUID draft = db.sql(
				"INSERT INTO creation_draft(owner_account_id,title,source_type,status,version,workspace_json) VALUES (:a,'fixture','independent','draft',1,'{\"schemaVersion\":1,\"capability\":\"video\"}'::jsonb) RETURNING id")
				.bind("a", ACCOUNT).map(r -> r.get(0, UUID.class)).one().block();
		db.sql("INSERT INTO video_storyboard_workspace(storyboard_id,draft_id,account_id,operation_id,request_hash) VALUES (:sb,:d,:a,gen_random_uuid(),'fixture')")
				.bind("sb", board).bind("d", draft).bind("a", ACCOUNT).then().block();
		var nodes = shots.stream().map(id -> {
			var node = new java.util.LinkedHashMap<String, Object>(Map.of("id", "shot:" + id, "kind", "shot", "refType",
					"shot", "refId", id.toString(), "x", 40, "y", 40));
			node.put("label", null);
			node.put("text", null);
			return node;
		}).toList();
		var document = new java.util.LinkedHashMap<String, Object>(
				Map.of("schemaVersion", 1, "storyboardId", board.toString(), "viewport",
						Map.of("panX", 0, "panY", 0, "scale", 1), "nodes", nodes, "edges", List.of()));
		document.put("activeBranchId", null);
		db.sql("INSERT INTO creation_canvas_document(id,draft_id,account_id,schema_version,revision,document) VALUES (gen_random_uuid(),:d,:a,1,1,CAST(:doc AS jsonb))")
				.bind("d", draft).bind("a", ACCOUNT).bind("doc", json(document)).then().block();
		return new Fixture(draft, board, shots.getFirst(), shots.getLast());
	}
	UUID ready(Fixture f, String action) {
		return db.sql(
				"INSERT INTO creation_canvas_agent_plan(id,account_id,operation_id,request_hash,draft_id,storyboard_id,base_draft_version,base_edit_version,base_canvas_revision,status,selected_node_ids,instruction,action,expires_at) "
						+ "SELECT gen_random_uuid(),:a,gen_random_uuid(),'fixture',d.id,s.id,d.version,s.edit_version,c.revision,'ready',CAST(:selected AS jsonb),'fixture',CAST(:action AS jsonb),now()+interval '30 minutes' "
						+ "FROM creation_draft d,video_storyboard s,creation_canvas_document c WHERE d.id=:d AND s.id=:sb AND c.draft_id=d.id RETURNING id")
				.bind("a", ACCOUNT).bind("d", f.draft()).bind("sb", f.storyboard())
				.bind("selected", json(List.of("shot:" + f.shot(), "shot:" + f.second()))).bind("action", action)
				.map(r -> r.get(0, UUID.class)).one().block();
	}
	UUID task(Fixture f, String phase) {
		return db.sql(
				"INSERT INTO video_production_task(storyboard_id,account_id,operation_id,mode,phase,target_duration_seconds,pricing_version,unit_price_cents,estimated_cost_cents,provider,model,platform_model_version) "
						+ "VALUES (:sb,:a,:op,'video',:phase,15,'v1',1,15,'sandbox','sandbox-video-v1',1) RETURNING id")
				.bind("sb", f.storyboard()).bind("a", ACCOUNT).bind("op", UUID.randomUUID().toString())
				.bind("phase", phase).map(r -> r.get(0, UUID.class)).one().block();
	}
	JsonNode apply(UUID id, int status) {
		String body = client().post().uri("/api/creation-assistant/canvas/plans/{id}/apply", id)
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of()).exchange().expectStatus().isEqualTo(status).expectBody(String.class).returnResult()
				.getResponseBody();
		try {
			return JSON.readTree(body).path("data");
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}
	static String edit(Fixture f, String patch, boolean append) {
		return "{\"kind\":\"edit\",\"actions\":[{\"kind\":\"update-shot\",\"patch\":{\"shotId\":\"" + f.shot() + "\","
				+ patch.substring(1) + "}"
				+ (append
						? ",{\"kind\":\"append-shot\",\"shot\":{\"visual\":\"appended visual\",\"narration\":\"\",\"plannedSeconds\":5,\"cameraMove\":\"固定机位\",\"anchorImageIndex\":0}}"
						: "")
				+ "]}";
	}
	static String variant(Fixture f) {
		return json(Map.of("kind", "variant", "title", "new variant", "shotIds", List.of(f.shot().toString())));
	}
	static String prepare(Fixture f, String mode) {
		return json(Map.of("kind", "prepare-generation", "mode", mode, "shotId", f.shot().toString()));
	}
	String column(String table, String column, UUID id) {
		return db.sql("SELECT " + column + "::text FROM " + table + " WHERE id=:id").bind("id", id)
				.map(r -> r.get(0, String.class)).one().block();
	}
	long count(String table, String column, UUID id) {
		return db.sql("SELECT count(*) FROM " + table + " WHERE " + column + "=:id").bind("id", id)
				.map(r -> r.get(0, Long.class)).one().block();
	}
	static String json(Object value) {
		try {
			return JSON.writeValueAsString(value);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}
}
