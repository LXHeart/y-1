package com.grassland.intelligence.creationcanvas;

import static org.assertj.core.api.Assertions.*;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"ai.video-generation.worker-enabled=false"})
class CanvasPlanConcurrencyIT extends CanvasActionFixtureSupport {
	@Test
	void twoConcurrentApplyCallsReturnTheSamePersistedResultAndWriteOnce() throws Exception {
		var f = fixture();
		UUID plan = ready(f, edit(f, "{\"visual\":\"once\"}", true));
		var first = CompletableFuture.supplyAsync(() -> apply(plan, 200));
		var second = CompletableFuture.supplyAsync(() -> apply(plan, 200));
		assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
		assertThat(column("video_storyboard", "edit_version", f.storyboard())).isEqualTo("2");
		assertThat(count("video_shot", "storyboard_id", f.storyboard())).isEqualTo(3);
	}

	@Test
	void versionsAreRereadAfterTheWaitingParentLock() throws Exception {
		for (String kind : List.of("draft", "canvas")) {
			var f = fixture();
			UUID plan = ready(f, edit(f, "{\"visual\":\"stale\"}", false));
			try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
					POSTGRES.getPassword())) {
				connection.setAutoCommit(false);
				try (var lock = connection.prepareStatement("SELECT id FROM creation_draft WHERE id=? FOR UPDATE")) {
					lock.setObject(1, f.draft());
					lock.executeQuery().close();
				}
				var response = CompletableFuture.supplyAsync(() -> apply(plan, 409));
				long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
				boolean waiting = false;
				while (!response.isDone() && System.nanoTime() < deadline) {
					try (var query = connection.createStatement()) {
						query.execute("SELECT pg_stat_clear_snapshot()");
						try (var rows = query.executeQuery(
								"SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%creation_draft%'")) {
							rows.next();
							if (rows.getInt(1) > 0) {
								waiting = true;
								break;
							}
						}
					}
					Thread.sleep(10);
				}
				try (var update = connection.prepareStatement("draft".equals(kind)
						? "UPDATE creation_draft SET version=version+1 WHERE id=?"
						: "UPDATE creation_canvas_document SET revision=revision+1 WHERE draft_id=?")) {
					update.setObject(1, f.draft());
					update.executeUpdate();
				}
				connection.commit();
				assertThat(waiting).isTrue();
				response.get(10, TimeUnit.SECONDS);
			}
			assertThat(column("video_shot", "visual", f.shot())).isEqualTo("original");
			assertThat(column("creation_canvas_agent_plan", "status", plan)).isEqualTo("ready");
		}
	}

	@Test
	void failureSavingApplyResultRollsBackBothEditsAndDerivedProjects() {
		var f = fixture();
		for (String action : List.of(edit(f, "{\"visual\":\"must rollback\"}", true), variant(f))) {
			UUID plan = ready(f, action);
			db.sql("CREATE FUNCTION task102_fail_apply() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='applied' THEN RAISE EXCEPTION 'injected failure'; END IF; RETURN NEW; END $$")
					.then().block();
			db.sql("CREATE TRIGGER task102_fail_apply BEFORE UPDATE ON creation_canvas_agent_plan FOR EACH ROW EXECUTE FUNCTION task102_fail_apply()")
					.then().block();
			try {
				apply(plan, 500);
			} finally {
				db.sql("DROP TRIGGER task102_fail_apply ON creation_canvas_agent_plan").then().block();
				db.sql("DROP FUNCTION task102_fail_apply()").then().block();
			}
			assertThat(count("video_shot", "storyboard_id", f.storyboard())).isEqualTo(2);
			assertThat(count("video_storyboard_variant", "parent_storyboard_id", f.storyboard())).isZero();
			assertThat(column("video_shot", "visual", f.shot())).isEqualTo("original");
			assertThat(column("video_storyboard", "edit_version", f.storyboard())).isEqualTo("1");
			assertThat(column("creation_canvas_agent_plan", "status", plan)).isEqualTo("ready");
		}
	}
}
