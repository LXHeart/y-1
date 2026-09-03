package com.grassland.intelligence.videoproduction;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** video_shot 读写（任务书 #64 卡1）。 */
@Component
public class VideoShotRepository {

	private static final String COLS = "id::text, storyboard_id::text, seq, visual, narration, "
			+ "planned_seconds, camera_move, anchor_image_index, prompt, status, anchor_media_id::text, "
			+ "anchor_source, created_at, updated_at";

	/** JOIN 查询用的带别名列表（别名列名与 COLS 一致，共用同一个 map）。 */
	private static final String JOIN_COLS = "s.id::text, s.storyboard_id::text, s.seq, s.visual, "
			+ "s.narration, s.planned_seconds, s.camera_move, s.anchor_image_index, s.prompt, "
			+ "s.status, s.anchor_media_id::text, s.anchor_source, s.created_at, s.updated_at";

	private final DatabaseClient db;

	public VideoShotRepository(DatabaseClient db) {
		this.db = db;
	}

	/**
	 * 逐镜写入（卡3 SSE 边收边落）。同 (storyboard_id, seq) 重放时覆盖内容而非报错—— LLM 流可能重发某一镜，UNIQUE
	 * 冲突不该让整条流失败。
	 */
	public Mono<VideoShot> upsert(UUID storyboardId, int seq, String visual, String narration, int plannedSeconds,
			String cameraMove, int anchorImageIndex, String prompt) {
		return db
				.sql("INSERT INTO video_shot(storyboard_id,seq,visual,narration,planned_seconds,"
						+ "camera_move,anchor_image_index,prompt) "
						+ "VALUES(CAST(:sb AS uuid),:seq,:visual,:narration,:planned,:move,:anchor,:prompt) "
						+ "ON CONFLICT(storyboard_id,seq) DO UPDATE SET visual=EXCLUDED.visual,"
						+ "narration=EXCLUDED.narration,planned_seconds=EXCLUDED.planned_seconds,"
						+ "camera_move=EXCLUDED.camera_move,anchor_image_index=EXCLUDED.anchor_image_index,"
						+ "prompt=EXCLUDED.prompt,updated_at=now() RETURNING " + COLS)
				.bind("sb", storyboardId.toString()).bind("seq", seq).bind("visual", visual)
				.bind("narration", narration).bind("planned", plannedSeconds).bind("move", cameraMove)
				.bind("anchor", anchorImageIndex).bind("prompt", prompt).map(VideoShotRepository::map).one();
	}

	public Flux<VideoShot> findByStoryboard(UUID storyboardId) {
		return db.sql("SELECT " + COLS + " FROM video_shot WHERE storyboard_id=CAST(:sb AS uuid) ORDER BY seq")
				.bind("sb", storyboardId.toString()).map(VideoShotRepository::map).all();
	}

	/** worker 内部按 id 取镜头（无归属闸——TTS/合成 worker 已在信任边界内）。 */
	public Mono<VideoShot> findById(UUID id) {
		return db.sql("SELECT " + COLS + " FROM video_shot WHERE id=CAST(:id AS uuid)").bind("id", id.toString())
				.map(VideoShotRepository::map).one();
	}

	/** 镜头按 owner 定位：storyboard.account_id 是唯一归属闸，越权取不到行（调用方 404）。 */
	public Mono<VideoShot> findByIdForAccount(UUID id, String accountId) {
		return db
				.sql("SELECT " + JOIN_COLS + " FROM video_shot s " + "JOIN video_storyboard b ON b.id=s.storyboard_id "
						+ "WHERE s.id=CAST(:id AS uuid) AND b.account_id=:accountId")
				.bind("id", id.toString()).bind("accountId", accountId).map(VideoShotRepository::map).one();
	}

	/** 用户编辑（卡4）：只允许改内容字段，seq 与归属不动。 */
	public Mono<Boolean> updateContent(UUID id, String visual, String narration, int plannedSeconds, String cameraMove,
			int anchorImageIndex, String prompt) {
		return db
				.sql("UPDATE video_shot SET visual=:visual,narration=:narration,planned_seconds=:planned,"
						+ "camera_move=:move,anchor_image_index=:anchor,prompt=:prompt,updated_at=now() "
						+ "WHERE id=CAST(:id AS uuid)")
				.bind("id", id.toString()).bind("visual", visual).bind("narration", narration)
				.bind("planned", plannedSeconds).bind("move", cameraMove).bind("anchor", anchorImageIndex)
				.bind("prompt", prompt).fetch().rowsUpdated().map(rows -> rows > 0);
	}

	/** 镜头数（卡A 数量闸与末尾追加 seq 排定的数据源）。 */
	public Mono<Long> countByStoryboard(UUID storyboardId) {
		return db.sql("SELECT count(*) AS total FROM video_shot WHERE storyboard_id=CAST(:sb AS uuid)")
				.bind("sb", storyboardId.toString()).map(row -> row.get("total", Long.class)).one();
	}

	/** 删镜头（卡A）：行删除，seq 重排与 grouping 剔除由调用方在事务内收口。 */
	public Mono<Boolean> delete(UUID id) {
		return db.sql("DELETE FROM video_shot WHERE id=CAST(:id AS uuid)").bind("id", id.toString()).fetch()
				.rowsUpdated().map(rows -> rows > 0);
	}

	/**
	 * 单行 seq 重排（卡A）。UNIQUE(storyboard_id, seq) 是即时约束且 seq CHECK 1-30 禁止
	 * 先加偏移再归位的两段式——只能升序逐行重排（目标值只会不变或变小，每步必空闲）。
	 */
	public Mono<Boolean> setSeq(UUID id, int seq) {
		return db.sql("UPDATE video_shot SET seq=:seq,updated_at=now() WHERE id=CAST(:id AS uuid)")
				.bind("id", id.toString()).bind("seq", seq).fetch().rowsUpdated().map(rows -> rows > 0);
	}

	public Mono<Boolean> updateStatus(UUID id, String status) {
		return db.sql("UPDATE video_shot SET status=:status,updated_at=now() WHERE id=CAST(:id AS uuid)")
				.bind("id", id.toString()).bind("status", status).fetch().rowsUpdated().map(rows -> rows > 0);
	}

	/** AI 补图落锚（#65 卡2）：anchor_media_id + anchor_source='ai'；重入即替换旧图。 */
	public Mono<Boolean> attachAnchor(UUID id, UUID anchorMediaId) {
		return db
				.sql("UPDATE video_shot SET anchor_media_id=CAST(:media AS uuid),"
						+ "anchor_source='ai',updated_at=now() WHERE id=CAST(:id AS uuid)")
				.bind("id", id.toString()).bind("media", anchorMediaId.toString()).fetch().rowsUpdated()
				.map(rows -> rows > 0);
	}

	static VideoShot map(Row r, RowMetadata m) {
		return new VideoShot(UUID.fromString(r.get("id", String.class)),
				UUID.fromString(r.get("storyboard_id", String.class)), r.get("seq", Integer.class),
				r.get("visual", String.class), r.get("narration", String.class),
				r.get("planned_seconds", Integer.class), r.get("camera_move", String.class),
				r.get("anchor_image_index", Integer.class), r.get("prompt", String.class),
				r.get("status", String.class), uuid(r.get("anchor_media_id", String.class)),
				r.get("anchor_source", String.class), r.get("created_at", OffsetDateTime.class),
				r.get("updated_at", OffsetDateTime.class));
	}

	private static UUID uuid(String value) {
		return value == null ? null : UUID.fromString(value);
	}
}
