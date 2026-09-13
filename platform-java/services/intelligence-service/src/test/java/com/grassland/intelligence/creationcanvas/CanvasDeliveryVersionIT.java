package com.grassland.intelligence.creationcanvas;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@TestPropertySource(properties = {"ai.video-generation.worker-enabled=false"})
class CanvasDeliveryVersionIT extends CanvasActionFixtureSupport {
	record Video(Fixture project, UUID task, UUID media) {
	}

	@Test
	void committedDeliveryPatchPreservesInputsAndHistoricalExportsKeepTheirOriginalVideo() {
		var f = video();
		save(f.project(), workspace(f, 0, "旧标题"), 200);
		save(f.project(), Map.of("delivery", Map.of("titleOrOpening", "新的交付标题")), 200);
		var current = draft(f.project());
		assertThat(current.path("workspace").path("inputs").path("preserved").asText()).isEqualTo("kept");
		assertThat(current.path("workspace").path("brief").path("goal").asText()).isEqualTo("task requirement");
		assertThat(current.path("workspace").path("resultRefs").get(0).path("recomposeSeq").asInt()).isZero();
		int oldVersion = current.path("version").asInt();
		UUID newMedia = media();
		db.sql("UPDATE video_production_task SET recompose_seq=1,final_media_id=:media WHERE id=:id")
				.bind("media", newMedia).bind("id", f.task()).then().block();
		// A text-only edit can retain a previously confirmed, now older result.
		save(f.project(), Map.of("delivery", Map.of("bodyOrDescription", "说明")), 200);
		var next = new Video(f.project(), f.task(), newMedia);
		save(f.project(), workspace(next, 1, "最新交付"), 200);
		var old = export(f.project(), oldVersion, 200);
		var ref = old.path("manifest").path("resultRefs").get(0);
		assertThat(ref.path("id").asText()).isEqualTo(f.media().toString());
		assertThat(ref.path("recomposeSeq").asInt()).isZero();
		assertThat(old.path("manifest").path("videoVersionReferenceMissing").asBoolean()).isFalse();
		assertThat(old.path("manifest").path("delivery").path("titleOrOpening").asText()).isEqualTo("新的交付标题");
		assertThat(count("video_production_task", "storyboard_id", f.project().storyboard())).isEqualTo(1);
		assertThat(column("video_storyboard", "edit_version", f.project().storyboard())).isEqualTo("1");
	}

	@Test
	void changingTheSequenceCannotBypassValidationByReusingTheSameMediaId() {
		var f = video();
		save(f.project(), workspace(f, 0, "title"), 200);
		int version = draft(f.project()).path("version").asInt();
		save(f.project(), workspace(f, 1, "tampered"), 409);
		for (Object value : List.of(-1, 1.5, "0"))
			save(f.project(), workspace(f, value, "invalid"), 400);
		assertThat(draft(f.project()).path("version").asInt()).isEqualTo(version);
		db.sql("UPDATE video_production_task SET phase='generating' WHERE id=:id").bind("id", f.task()).then().block();
		var changed = new LinkedHashMap<>(workspace(f, 0, "invalid"));
		var ref = new LinkedHashMap<>(reference(f, 0));
		ref.put("position", 1);
		changed.put("resultRefs", List.of(ref));
		save(f.project(), changed, 409);
	}

	@Test
	void ownButDifferentProjectAndForeignTasksCannotBecomeTheCurrentDraftResult() {
		var first = video();
		var other = video();
		save(first.project(), workspace(other, 0, "cannot rebind"), 409);
		db.sql("UPDATE video_production_task SET account_id=:other WHERE id=:id")
				.bind("other", UUID.randomUUID().toString()).bind("id", first.task()).then().block();
		save(first.project(), workspace(first, 0, "foreign"), 404);
		assertThat(draft(first.project()).path("version").asInt()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"video", "subtitle"})
	void boundDraftCannotOmitVideoIdentityByRemovingWorkspaceMarkers(String role) {
		var f = video();
		var unversioned = Map.of("id", f.media().toString(), "refType", "media", "role", role);
		var withoutMarker = Map.<String, Object>of("schemaVersion", 1, "capability", "video", "resultRefs",
				List.of(unversioned));
		var before = draft(f.project());
		save(f.project(), withoutMarker, 400).expectBody().jsonPath("$.code").isEqualTo("CANVAS_INVALID_INPUT");
		assertThat(draft(f.project())).isEqualTo(before);
		save(f.project(), workspace(f, 0, "confirmed"), 200);
		before = draft(f.project());
		save(f.project(), withoutMarker, 400).expectBody().jsonPath("$.code").isEqualTo("CANVAS_INVALID_INPUT");
		assertThat(draft(f.project())).isEqualTo(before);
	}

	@ParameterizedTest
	@CsvSource({"video,false", "subtitle,false", "video,true", "subtitle,true"})
	void unboundLegacyReferencesRemainCompatibleAndRetainedReferencesCanBeEdited(String role, boolean videoMarker) {
		var f = video();
		db.sql("DELETE FROM video_storyboard_workspace WHERE draft_id=:draft").bind("draft", f.project().draft()).then()
				.block();
		var legacy = new LinkedHashMap<>(Map.<String, Object>of("schemaVersion", 1, "capability", "video", "resultRefs",
				List.of(Map.of("id", f.media().toString(), "refType", "media", "role", role))));
		if (videoMarker)
			legacy.put("inputs", Map.of("video", Map.of("storyboardId", f.project().storyboard().toString())));
		save(f.project(), legacy, 200);
		db.sql("INSERT INTO video_storyboard_workspace(storyboard_id,draft_id,account_id,operation_id,request_hash) VALUES (:sb,:draft,:a,gen_random_uuid(),'fixture')")
				.bind("sb", f.project().storyboard()).bind("draft", f.project().draft()).bind("a", ACCOUNT).then()
				.block();
		save(f.project(), Map.of("delivery", Map.of("titleOrOpening", "edit retained legacy result")), 200);
		assertThat(draft(f.project()).path("workspace").path("resultRefs").get(0).path("role").asText())
				.isEqualTo(role);
	}

	@Test
	void unboundReferencesThatDeclareAVideoVersionStillValidateTheTask() {
		var f = video();
		db.sql("DELETE FROM video_storyboard_workspace WHERE draft_id=:draft").bind("draft", f.project().draft()).then()
				.block();
		save(f.project(), workspace(f, 1, "stale version"), 409).expectBody().jsonPath("$.code")
				.isEqualTo("CANVAS_VERSION_CONFLICT");
		var incomplete = new LinkedHashMap<>(workspace(f, 0, "missing task"));
		var ref = new LinkedHashMap<>(reference(f, 0));
		ref.remove("productionTaskId");
		incomplete.put("resultRefs", List.of(ref));
		save(f.project(), incomplete, 400).expectBody().jsonPath("$.code").isEqualTo("CANVAS_INVALID_INPUT");
		assertThat(draft(f.project()).path("version").asInt()).isEqualTo(1);
	}

	@Test
	void archivedDraftStaysReadOnlyAndLegacyOrExpiredReferencesAreExplicitInManifest() {
		var f = video();
		save(f.project(), workspace(f, 0, "title"), 200);
		db.sql("UPDATE media_reference SET expires_at=now()-interval '1 second' WHERE id=:id").bind("id", f.media())
				.then().block();
		var exported = export(f.project(), draft(f.project()).path("version").asInt(), 200);
		assertThat(exported.path("downloads").get(0).path("unavailable").asText()).isEqualTo("expired");
		assertThat(exported.path("downloads").get(0).has("url")).isFalse();
		client().post().uri("/api/creation-drafts/{id}/archive", f.project().draft())
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).exchange().expectStatus().isOk();
		save(f.project(), Map.of("delivery", Map.of("titleOrOpening", "forbidden")), 409);
		var legacy = fixture();
		var manifest = export(legacy, 1, 200).path("manifest");
		assertThat(manifest.path("videoVersionReferenceMissing").asBoolean()).isTrue();
		assertThat(manifest.path("resultRefs").size()).isZero();
	}

	private Video video() {
		var project = fixture();
		UUID task = task(project, "succeeded");
		UUID media = media();
		db.sql("UPDATE video_production_task SET final_media_id=:media WHERE id=:id").bind("media", media)
				.bind("id", task).then().block();
		db.sql("UPDATE video_storyboard SET status='committed' WHERE id=:id").bind("id", project.storyboard()).then()
				.block();
		return new Video(project, task, media);
	}
	private UUID media() {
		return db.sql(
				"INSERT INTO media_reference(owner_account_id,purpose,object_key,mime_type,status) VALUES (:a,'video_master',:key,'video/mp4','active') RETURNING id")
				.bind("a", ACCOUNT).bind("key", UUID.randomUUID().toString()).map(row -> row.get(0, UUID.class)).one()
				.block();
	}
	private Map<String, Object> reference(Video video, Object seq) {
		return Map.of("id", video.media().toString(), "refType", "media", "role", "video", "storyboardId",
				video.project().storyboard().toString(), "productionTaskId", video.task().toString(), "recomposeSeq",
				seq);
	}
	private Map<String, Object> workspace(Video video, Object seq, String title) {
		return Map.of("schemaVersion", 1, "capability", "video", "inputs",
				Map.of("preserved", "kept", "video", Map.of("storyboardId", video.project().storyboard().toString())),
				"brief", Map.of("goal", "task requirement"), "delivery",
				Map.of("version", 1, "platform", "douyin", "contentForm", "video", "titleOrOpening", title),
				"resultRefs", List.of(reference(video, seq)));
	}
	private JsonNode draft(Fixture project) {
		return parse(client().get().uri("/api/creation-drafts/{id}", project.draft())
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).exchange().expectStatus().isOk()
				.expectBody(String.class).returnResult().getResponseBody());
	}
	private WebTestClient.ResponseSpec save(Fixture project, Map<String, Object> workspace, int status) {
		return client().put().uri("/api/creation-drafts/{id}", project.draft())
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", draft(project).path("version").asInt(), "workspace", workspace))
				.exchange().expectStatus().isEqualTo(status);
	}
	private JsonNode export(Fixture project, int version, int status) {
		return parse(client().post().uri("/api/creation-drafts/{id}/exports", project.draft())
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("version", version, "format", "bundle-manifest")).exchange().expectStatus()
				.isEqualTo(status).expectBody(String.class).returnResult().getResponseBody());
	}
	private JsonNode parse(String body) {
		try {
			return JSON.readTree(body).path("data");
		} catch (Exception e) {
			throw new AssertionError(body, e);
		}
	}
}
