package com.grassland.intelligence.creationcanvas;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * C102-01: exercise the public writes against real parent state and PostgreSQL
 * locks.
 */
@TestPropertySource(properties = {"ai.video-generation.worker-enabled=false"})
class CanvasProjectAccessIT extends IntelligenceItSupport {
	private static final String ACCOUNT = "10200000-0000-4000-8000-000000000001";
	private static final String OTHER = "10200000-0000-4000-8000-000000000002";

	@Test
	void archivedParentRejectsContentAndCanvasUsingCurrentVersion() {
		var p = project(true);
		client().post().uri("/api/creation-drafts/{id}/archive", p.draft())
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.version").isEqualTo(2);
		edit(p, ACCOUNT, 409);
		put(p, 0, 409);
		client().put().uri("/api/creation-drafts/{id}", p.draft())
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", 2, "title", "changed")).exchange().expectStatus().isEqualTo(409);
		assertThat(visual(p)).isEqualTo("original");
	}

	@Test
	void deletedAndForeignParentsNeverAuthorizeContent() {
		var p = project(true);
		edit(p, OTHER, 404);
		client().get().uri("/api/creation-drafts/{id}/canvas", p.draft()).exchange().expectStatus().isUnauthorized();
		client().delete().uri("/api/creation-drafts/{id}", p.draft())
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).exchange().expectStatus().isOk();
		edit(p, ACCOUNT, 404);
		put(p, 0, 404);
		assertThat(visual(p)).isEqualTo("original");
	}

	@Test
	void legacyUnboundStoryboardStillAcceptsVersionlessContent() {
		var p = project(false);
		edit(p, ACCOUNT, 200);
		assertThat(visual(p)).isEqualTo("changed");
		assertThat(db.sql("SELECT count(*) FROM video_storyboard_workspace WHERE storyboard_id=:id")
				.bind("id", p.storyboard()).map(row -> row.get(0, Long.class)).one().block()).isZero();
	}

	@Test
	void savingTheSameDocumentDoesNotRotateRevisionOrDraftVersion() {
		var p = project(true);
		put(p, 0, 200);
		put(p, 1, 200);
		client().get().uri("/api/creation-drafts/{id}/canvas", p.draft())
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.revision").isEqualTo(1);
		assertThat(db.sql("SELECT version FROM creation_draft WHERE id=:id").bind("id", p.draft())
				.map(row -> row.get(0, Integer.class)).one().block()).isEqualTo(1);
	}

	@Test
	void contentWriteWaitsForParentLockAndRechecksState() throws Exception {
		var p = project(true);
		assertWaitsForArchive(p, () -> client().put().uri("/api/video-production/shots/{id}/content", p.shot())
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("visual", "changed")).exchange().returnResult(Map.class).getStatus().value());
		assertThat(visual(p)).isEqualTo("original");
	}

	@Test
	void canvasWriteWaitsForParentLockAndRechecksState() throws Exception {
		var p = project(true);
		assertWaitsForArchive(p, () -> client().put().uri("/api/creation-drafts/{id}/canvas", p.draft())
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedRevision", 0, "document", document(p))).exchange().returnResult(Map.class)
				.getStatus().value());
		assertThat(db.sql("SELECT count(*) FROM creation_canvas_document WHERE draft_id=:id").bind("id", p.draft())
				.map(row -> row.get(0, Long.class)).one().block()).isZero();
	}

	@Test
	void simultaneousLegacyBindingCreatesOnlyOneDraft() throws Exception {
		var p = project(false);
		var before = db.sql("SELECT count(*) FROM creation_draft").map(row -> row.get(0, Long.class)).one().block();
		java.util.function.Supplier<String> bind = () -> {
			var body = client().post().uri("/api/video-production/storyboards/{id}/workspace", p.storyboard())
					.header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
					.contentType(MediaType.APPLICATION_JSON)
					.bodyValue(Map.of("operationId", UUID.randomUUID().toString())).exchange().expectStatus().isOk()
					.expectBody(Map.class).returnResult().getResponseBody();
			return ((Map<?, ?>) ((Map<?, ?>) body.get("data")).get("project")).get("id").toString();
		};
		var first = CompletableFuture.supplyAsync(bind);
		var second = CompletableFuture.supplyAsync(bind);
		assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
		assertThat(db.sql("SELECT count(*) FROM creation_draft").map(row -> row.get(0, Long.class)).one().block())
				.isEqualTo(before + 1);
	}

	private void assertWaitsForArchive(Project p, java.util.function.Supplier<Integer> write) throws Exception {
		try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
				POSTGRES.getPassword())) {
			connection.setAutoCommit(false);
			try (var lock = connection.prepareStatement("SELECT id FROM creation_draft WHERE id=? FOR UPDATE")) {
				lock.setObject(1, p.draft());
				lock.executeQuery().close();
			}
			var response = CompletableFuture.supplyAsync(write);
			// Wait until the request either waits on the parent lock or incorrectly
			// finishes.
			long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
			boolean waiting = false;
			while (!response.isDone() && System.nanoTime() < deadline) {
				// PostgreSQL caches activity snapshots for this transaction; observe the newly
				// blocked backend.
				try (var statement = connection.createStatement()) {
					statement.execute("SELECT pg_stat_clear_snapshot()");
				}
				try (var statement = connection.createStatement();
						var rows = statement
								.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' "
										+ "AND query LIKE '%creation_draft%'")) {
					rows.next();
					if (rows.getInt(1) > 0) {
						waiting = true;
						break;
					}
				}
				TimeUnit.MILLISECONDS.sleep(20);
			}
			try (var update = connection
					.prepareStatement("UPDATE creation_draft SET status='archived', version=version+1 WHERE id=?")) {
				update.setObject(1, p.draft());
				update.executeUpdate();
			}
			connection.commit();
			assertThat(response.get(10, TimeUnit.SECONDS)).isEqualTo(409);
			assertThat(waiting).as("writes must lock the bound draft before the storyboard").isTrue();
		}
	}

	private record Project(UUID draft, UUID storyboard, UUID shot) {
	}

	private Project project(boolean bound) {
		UUID storyboard = db
				.sql("INSERT INTO video_storyboard(account_id, target_duration_seconds, request_payload) "
						+ "VALUES (:account, 15, '{}'::jsonb) RETURNING id")
				.bind("account", ACCOUNT).map(row -> row.get(0, UUID.class)).one().block();
		UUID shot = db.sql("INSERT INTO video_shot(storyboard_id, seq, visual, narration, planned_seconds, "
				+ "camera_move, anchor_image_index, prompt) VALUES (:sb, 1, 'original', 'n', 5, '固定机位', 0, 'original') RETURNING id")
				.bind("sb", storyboard).map(row -> row.get(0, UUID.class)).one().block();
		UUID draft = db
				.sql("INSERT INTO creation_draft(id, owner_account_id, title, source_type, status, version, "
						+ "workspace_json) VALUES (gen_random_uuid(), :account, 'project', 'independent', 'draft', 1, "
						+ "'{\"schemaVersion\":1,\"capability\":\"video\"}'::jsonb) RETURNING id")
				.bind("account", ACCOUNT).map(row -> row.get(0, UUID.class)).one().block();
		if (bound)
			db.sql("INSERT INTO video_storyboard_workspace(storyboard_id, draft_id, account_id, operation_id, request_hash) "
					+ "VALUES (:sb, :draft, :account, gen_random_uuid(), 'test')").bind("sb", storyboard)
					.bind("draft", draft).bind("account", ACCOUNT).then().block();
		return new Project(draft, storyboard, shot);
	}

	private void edit(Project p, String account, int status) {
		client().put().uri("/api/video-production/shots/{id}/content", p.shot())
				.header("X-Grassland-Identity", sign(account, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("visual", "changed")).exchange().expectStatus().isEqualTo(status);
	}

	private Map<String, Object> document(Project p) {
		Map<String, Object> document = new java.util.LinkedHashMap<>(
				Map.of("schemaVersion", 1, "storyboardId", p.storyboard().toString(), "viewport",
						Map.of("scale", 1, "panX", 0, "panY", 0), "nodes", List.of(), "edges", List.of()));
		document.put("activeBranchId", null);
		return document;
	}

	private void put(Project p, int revision, int status) {
		client().put().uri("/api/creation-drafts/{id}/canvas", p.draft())
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedRevision", revision, "document", document(p))).exchange().expectStatus()
				.isEqualTo(status);
	}

	private String visual(Project p) {
		return db.sql("SELECT visual FROM video_shot WHERE id=:id").bind("id", p.shot())
				.map(row -> row.get(0, String.class)).one().block();
	}
}
