package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 任务书 #70 卡A 后端：分镜编辑期增删镜头契约——POST 末尾追加（缺省归一 + prompt=visual 兜底）、 DELETE
 * 顺位重排（UNIQUE(storyboard_id,seq) 即时约束下升序逐行）+ grouping 悬空 id 剔除、 数量界（≥30 上限 / 剩余
 * <3 下限）、draft 闸与属主闸；建任务 visual 空白防呆（400）。
 */
@DisplayName("Storyboard shot structure add/remove (Card A)")
@TestPropertySource(properties = {"ai.video-generation.worker-enabled=false"})
class ShotStructureIT extends IntelligenceItSupport {

	private static final String ACCOUNT = "43334333-4333-4333-4333-433343334333";
	private static final String OTHER = "44444444-4444-4444-4444-444444444444";

	@MockitoBean
	CreditsClient credits;

	@MockitoBean
	ObjectStorageAdapter storage;

	@Autowired
	VideoStoryboardRepository storyboardRows;

	@BeforeEach
	void cleanAndSeed() {
		reset(credits, storage);
		when(credits.consume(anyString(), any(CreditFeature.class), anyString())).thenAnswer(invocation -> Mono.just(
				new CreditCharge(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))));
		when(credits.refund(any(), anyString())).thenReturn(Mono.empty());
		when(storage.presignDownload(anyString(), anyLong()))
				.thenAnswer(invocation -> java.net.URI.create("https://media.example.test/signed"));

		db.sql("DELETE FROM video_shot_take").then().then(db.sql("DELETE FROM video_shot_audio").then())
				.then(db.sql("DELETE FROM video_production_task").then()).then(db.sql("DELETE FROM video_shot").then())
				.then(db.sql("DELETE FROM video_storyboard").then()).block(Duration.ofSeconds(10));
	}

	@Test
	@DisplayName("POST 默认值落库：seq 末尾追加、visual/narration 允空、prompt=visual、时长钳 4-6")
	void addShotAppendsWithDefaults() {
		UUID storyboardId = seedStoryboard();
		seedShots(storyboardId, 2);

		client().post().uri("/api/video-production/storyboards/{id}/shots", storyboardId)
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of()).exchange().expectStatus().isOk().expectBody().jsonPath("$.data.seq").isEqualTo(3)
				.jsonPath("$.data.visual").isEqualTo("").jsonPath("$.data.narration").isEqualTo("")
				.jsonPath("$.data.plannedSeconds").isEqualTo(5).jsonPath("$.data.cameraMove").isEqualTo("固定机位")
				.jsonPath("$.data.anchorImageIndex").isEqualTo(0).jsonPath("$.data.status").isEqualTo("draft");

		client().post().uri("/api/video-production/storyboards/{id}/shots", storyboardId)
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("visual", " 夜景特写 ", "narration", "旁白", "plannedSeconds", 9, "cameraMove", "环绕",
						"anchorImageIndex", 2))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.seq").isEqualTo(4)
				.jsonPath("$.data.visual").isEqualTo("夜景特写").jsonPath("$.data.plannedSeconds").isEqualTo(6)
				.jsonPath("$.data.cameraMove").isEqualTo("环绕");

		String promptRow = db.sql("SELECT prompt FROM video_shot " + "WHERE storyboard_id=CAST(:sb AS uuid) AND seq=3")
				.bind("sb", storyboardId.toString()).map(row -> row.get("prompt", String.class)).one()
				.block(Duration.ofSeconds(5));
		assertThat(promptRow).isEqualTo("");
	}

	@Test
	@DisplayName("POST 闸：上限 30、committed 409、非属主 404、负 anchorImageIndex 400")
	void addShotGates() {
		UUID storyboardId = seedStoryboard();
		seedShots(storyboardId, 30);

		client().post().uri("/api/video-production/storyboards/{id}/shots", storyboardId)
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of()).exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.error")
				.isEqualTo("镜头数已达上限 30");

		db.sql("DELETE FROM video_shot WHERE storyboard_id=CAST(:sb AS uuid) AND seq > 3")
				.bind("sb", storyboardId.toString()).then().block(Duration.ofSeconds(5));
		client().post().uri("/api/video-production/storyboards/{id}/shots", storyboardId)
				.header("X-Grassland-Identity", sign(OTHER, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of()).exchange().expectStatus().isNotFound();

		client().post().uri("/api/video-production/storyboards/{id}/shots", storyboardId)
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("anchorImageIndex", -1)).exchange().expectStatus().isBadRequest();

		db.sql("UPDATE video_storyboard SET status='committed' WHERE id=CAST(:id AS uuid)")
				.bind("id", storyboardId.toString()).then().block(Duration.ofSeconds(5));
		client().post().uri("/api/video-production/storyboards/{id}/shots", storyboardId)
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of()).exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.error")
				.isEqualTo("分镜已提交成片，不能再增删镜头");
	}

	@Test
	@DisplayName("DELETE：seq 升序重排无空洞；grouping 悬空 id 被剔除；响应 shotCount")
	void removeShotResequencesAndCleansGrouping() {
		UUID storyboardId = seedStoryboard();
		List<UUID> shotIds = seedShots(storyboardId, 5);
		UUID removed = shotIds.get(1);
		db.sql("UPDATE video_storyboard SET grouping=CAST(:g AS jsonb) WHERE id=CAST(:id AS uuid)")
				.bind("g",
						"{\"shots\":[{\"id\":\"" + removed + "\",\"groupId\":\"g1\"}],"
								+ "\"branches\":[{\"id\":\"b1\",\"name\":\"主版本\",\"shotIds\":[" + "\"" + shotIds.get(0)
								+ "\",\"" + removed + "\",\"" + shotIds.get(2) + "\"]}]}")
				.bind("id", storyboardId.toString()).then().block(Duration.ofSeconds(5));

		client().delete().uri("/api/video-production/shots/{id}", removed)
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.removed").isEqualTo(removed.toString()).jsonPath("$.data.shotCount")
				.isEqualTo(4);

		List<Integer> seqs = db
				.sql("SELECT seq FROM video_shot WHERE storyboard_id=CAST(:sb AS uuid) " + "ORDER BY seq")
				.bind("sb", storyboardId.toString()).map(row -> row.get("seq", Integer.class)).all().collectList()
				.block(Duration.ofSeconds(5));
		assertThat(seqs).containsExactly(1, 2, 3, 4);

		String grouping = db.sql("SELECT grouping::text AS g FROM video_storyboard WHERE id=CAST(:id AS uuid)")
				.bind("id", storyboardId.toString()).map(row -> row.get("g", String.class)).one()
				.block(Duration.ofSeconds(5));
		assertThat(grouping).doesNotContain(removed.toString());
		assertThat(grouping).contains(shotIds.get(0).toString()).contains(shotIds.get(2).toString());
	}

	@Test
	@DisplayName("DELETE 闸：剩余 3 镜 409、committed 409、非属主 404")
	void removeShotGates() {
		UUID storyboardId = seedStoryboard();
		List<UUID> shotIds = seedShots(storyboardId, 3);

		client().delete().uri("/api/video-production/shots/{id}", shotIds.get(0))
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).exchange().expectStatus().isEqualTo(409)
				.expectBody().jsonPath("$.error").isEqualTo("至少保留 3 个镜头");

		client().delete().uri("/api/video-production/shots/{id}", shotIds.get(0))
				.header("X-Grassland-Identity", sign(OTHER, "recommender")).exchange().expectStatus().isNotFound();

		seedShots(storyboardId, 2, 4);
		db.sql("UPDATE video_storyboard SET status='committed' WHERE id=CAST(:id AS uuid)")
				.bind("id", storyboardId.toString()).then().block(Duration.ofSeconds(5));
		client().delete().uri("/api/video-production/shots/{id}", shotIds.get(0))
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).exchange().expectStatus().isEqualTo(409)
				.expectBody().jsonPath("$.error").isEqualTo("分镜已提交成片，不能再增删镜头");
	}

	@Test
	@DisplayName("建任务防呆：存在 visual 空白镜头 → 400（付费预留之前拦截）")
	void createTaskRejectsBlankVisual() {
		UUID storyboardId = seedStoryboard();
		seedShots(storyboardId, 3);
		db.sql("UPDATE video_shot SET visual='' WHERE storyboard_id=CAST(:sb AS uuid) AND seq=2")
				.bind("sb", storyboardId.toString()).then().block(Duration.ofSeconds(5));

		client().post().uri("/api/video-production/tasks").header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("storyboardId", storyboardId.toString()))
				.exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error")
				.isEqualTo("存在未填写画面描述的镜头，请补全后再生成");

		Long taskRows = db.sql("SELECT count(*) AS total FROM video_production_task")
				.map(row -> row.get("total", Long.class)).one().block(Duration.ofSeconds(5));
		assertThat(taskRows).isZero();
	}

	// ---------------- helpers ----------------

	private UUID seedStoryboard() {
		return UUID.fromString(db.sql("""
				INSERT INTO video_storyboard(account_id, target_duration_seconds, request_payload)
				VALUES (:account, 20, CAST('{"images":[],"shopName":"店"}' AS jsonb))
				RETURNING id::text
				""").bind("account", ACCOUNT).map(row -> row.get("id", String.class)).one()
				.block(Duration.ofSeconds(5)));
	}

	/** 造 count 个镜头（seq 从 from 开始），按 seq 升序返回 id 列表。 */
	private List<UUID> seedShots(UUID storyboardId, int count, int from) {
		return db.sql("""
				INSERT INTO video_shot(storyboard_id, seq, visual, narration, planned_seconds,
				    camera_move, anchor_image_index, prompt, status)
				SELECT CAST(:sb AS uuid), :from + g - 1, '画面', '旁白', 5, '固定机位', 0, 'p', 'ready'
				FROM generate_series(1, :count) g
				RETURNING seq, id::text
				""").bind("sb", storyboardId.toString()).bind("from", from).bind("count", count)
				.map(row -> Map.entry(row.get("seq", Integer.class), UUID.fromString(row.get("id", String.class))))
				.all().collectList()
				.map(entries -> entries.stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList())
				.block(Duration.ofSeconds(5));
	}

	private List<UUID> seedShots(UUID storyboardId, int count) {
		return seedShots(storyboardId, count, 1);
	}
}
