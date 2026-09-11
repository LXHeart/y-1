package com.grassland.intelligence.creationcanvas;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 分镜 ↔ 草稿唯一关联（任务书 #100 C100-04 / V72 / API-07）。
 *
 * <p>
 * storyboard_id 主键 = 一分镜一关联；draft_id 唯一；(account_id, operation_id) 唯一支撑
 * 绑定响应丢失后的原键重放。写入口只有 {@link #insert}——冲突 DO NOTHING 后由服务层
 * 复读裁决（并发同分镜不同草稿 → 409，同草稿 → 幂等返回）。
 */
@Component
public class VideoCanvasWorkspaceRepository {

	private final DatabaseClient db;

	public VideoCanvasWorkspaceRepository(DatabaseClient db) {
		this.db = db;
	}

	public record WorkspaceBinding(UUID storyboardId, UUID draftId, String accountId, UUID operationId,
			String requestHash, OffsetDateTime createdAt) {
	}

	private static final String COLS = "storyboard_id::text, draft_id::text, account_id, operation_id::text, "
			+ "request_hash, created_at";

	/** 插入关联；分镜已被并发绑定返回 0 行（调用方复读裁决）。 */
	public Mono<Long> insert(WorkspaceBinding binding) {
		return db.sql("INSERT INTO video_storyboard_workspace(storyboard_id, draft_id, account_id, operation_id, "
						+ "request_hash) VALUES (CAST(:storyboard AS uuid), CAST(:draft AS uuid), :account, "
						+ "CAST(:operation AS uuid), :hash) ON CONFLICT (storyboard_id) DO NOTHING")
				.bind("storyboard", binding.storyboardId().toString())
				.bind("draft", binding.draftId().toString())
				.bind("account", binding.accountId())
				.bind("operation", binding.operationId().toString())
				.bind("hash", binding.requestHash())
				.fetch().rowsUpdated();
	}

	public Mono<WorkspaceBinding> findByStoryboard(UUID storyboardId) {
		return db.sql("SELECT " + COLS + " FROM video_storyboard_workspace WHERE storyboard_id=CAST(:id AS uuid)")
				.bind("id", storyboardId.toString())
				.map(VideoCanvasWorkspaceRepository::map)
				.one();
	}

	/** 幂等重放键：同账号同 operationId 的绑定行（跨账号不存在——插入时已绑定 account）。 */
	public Mono<WorkspaceBinding> findByAccountAndOperation(String accountId, UUID operationId) {
		return db.sql("SELECT " + COLS + " FROM video_storyboard_workspace "
						+ "WHERE account_id=:account AND operation_id=CAST(:operation AS uuid)")
				.bind("account", accountId)
				.bind("operation", operationId.toString())
				.map(VideoCanvasWorkspaceRepository::map)
				.one();
	}

	/**
	 * 存量候选（§6.3）：账号名下引用该分镜的视频草稿（inputs.video.storyboardId 合法输入引用）。
	 * 只取 id（上限 5 足以判定 0/1/多个），整行由 CreationDraftRepository 装载。
	 */
	public Flux<String> findCandidateDraftIds(String accountId, UUID storyboardId) {
		return db.sql("SELECT id::text FROM creation_draft "
						+ "WHERE owner_account_id=:account AND deleted_at IS NULL "
						+ "AND workspace_json->>'capability'='video' "
						+ "AND workspace_json->'inputs'->'video'->>'storyboardId'=:storyboard "
						+ "ORDER BY updated_at DESC, id LIMIT 5")
				.bind("account", accountId)
				.bind("storyboard", storyboardId.toString())
				.map(row -> row.get("id", String.class))
				.all();
	}

	private static WorkspaceBinding map(Row row, RowMetadata meta) {
		return new WorkspaceBinding(UUID.fromString(row.get("storyboard_id", String.class)),
				UUID.fromString(row.get("draft_id", String.class)), row.get("account_id", String.class),
				UUID.fromString(row.get("operation_id", String.class)), row.get("request_hash", String.class),
				row.get("created_at", OffsetDateTime.class));
	}
}
