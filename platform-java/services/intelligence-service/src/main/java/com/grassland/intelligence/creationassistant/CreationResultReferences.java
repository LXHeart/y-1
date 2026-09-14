package com.grassland.intelligence.creationassistant;

import com.grassland.intelligence.contentlibrary.ContentAssetRepository;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Typed handles are authorized when selected; unchanged expired handles do not
 * block text edits.
 */
@Component
public class CreationResultReferences {
	private final MediaReferenceRepository media;
	private final ContentAssetRepository assets;
	private final DatabaseClient db;

	public CreationResultReferences(MediaReferenceRepository media, ContentAssetRepository assets, DatabaseClient db) {
		this.media = media;
		this.assets = assets;
		this.db = db;
	}

	public Mono<Void> validateNew(Map<String, Object> workspace, Map<String, Object> previous, Caller caller) {
		return validateNew(workspace, previous, caller, null);
	}

	public Mono<Void> validateNew(Map<String, Object> workspace, Map<String, Object> previous, Caller caller,
			UUID draftId) {
		Set<Map<?, ?>> retained = new java.util.HashSet<>(refs(previous));
		return Flux.fromIterable(refs(workspace)).filter(ref -> !retained.contains(ref))
				.concatMap(ref -> validate(ref, caller).then(validateVideo(ref, previous, caller, draftId))).then();
	}

	private Mono<Void> validateVideo(Map<?, ?> ref, Map<String, Object> previous, Caller caller, UUID draftId) {
		if (!"video".equals(ref.get("role")) && !"subtitle".equals(ref.get("role")))
			return Mono.empty();
		Mono<String> expectedBoard = draftId == null
				? Mono.empty()
				: db.sql(
						"SELECT storyboard_id::text FROM video_storyboard_workspace WHERE draft_id=:draft AND account_id=:account")
						.bind("draft", draftId).bind("account", caller.accountId()).map(row -> row.get(0, String.class))
						.one();
		String priorBoard = videoBoard(previous);
		return expectedBoard.defaultIfEmpty("").flatMap(bound -> {
			// Only unbound legacy workflows may introduce an unversioned video handle.
			// A full workspace replacement cannot erase the server-owned binding.
			if (bound.isEmpty() && !ref.containsKey("productionTaskId") && !ref.containsKey("recomposeSeq"))
				return Mono.empty();
			return validateVideoVersion(ref, caller, bound.isEmpty() && priorBoard != null ? priorBoard : bound);
		});
	}

	private Mono<Void> validateVideoVersion(Map<?, ?> ref, Caller caller, String bound) {
		UUID taskId, storyboardId, mediaId;
		Object sequence = ref.get("recomposeSeq");
		try {
			taskId = exactUuid(ref.get("productionTaskId"));
			storyboardId = exactUuid(ref.get("storyboardId"));
			mediaId = exactUuid(ref.get("id"));
			if (!"media".equals(ref.get("refType")) || !(sequence instanceof Integer || sequence instanceof Long)
					|| ((Number) sequence).longValue() < 0 || ((Number) sequence).longValue() > Integer.MAX_VALUE)
				throw new IllegalArgumentException();
		} catch (Exception invalid) {
			return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "视频结果必须包含任务、分镜、媒体和成片版本"));
		}
		if (!bound.isEmpty() && !bound.equals(storyboardId.toString()))
			return Mono.error(new IntelligenceException(409, "CANVAS_OPERATION_CONFLICT", "视频结果不属于当前草稿分镜"));
		return db.sql(
				"SELECT t.storyboard_id,t.recompose_seq,t.final_media_id,t.srt_media_id,t.phase FROM video_production_task t "
						+ "JOIN video_storyboard s ON s.id=t.storyboard_id WHERE t.id=:id AND t.account_id=:account AND s.account_id=:account FOR UPDATE OF t")
				.bind("id", taskId).bind("account", caller.accountId()).map(row -> {
					if (!storyboardId.equals(row.get("storyboard_id", UUID.class)))
						throw new IntelligenceException(409, "CANVAS_OPERATION_CONFLICT", "任务与分镜不匹配");
					UUID actual = row.get("video".equals(ref.get("role")) ? "final_media_id" : "srt_media_id",
							UUID.class);
					if (!"succeeded".equals(row.get("phase", String.class))
							|| ((Number) sequence).intValue() != row.get("recompose_seq", Integer.class)
							|| !mediaId.equals(actual))
						throw new IntelligenceException(409, "CANVAS_VERSION_CONFLICT", "成片版本已变化，请重新确认结果");
					return true;
				}).one()
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "CANVAS_RESOURCE_NOT_FOUND", "制作任务不存在")))
				.then();
	}
	private static UUID exactUuid(Object raw) {
		if (!(raw instanceof String value))
			throw new IllegalArgumentException();
		UUID id = UUID.fromString(value);
		if (!id.toString().equalsIgnoreCase(value))
			throw new IllegalArgumentException();
		return id;
	}
	private static String videoBoard(Map<String, Object> workspace) {
		return workspace.get("inputs") instanceof Map<?, ?> inputs && inputs.get("video") instanceof Map<?, ?> video
				&& video.get("storyboardId") instanceof String id ? id : null;
	}

	private Mono<?> validate(Map<?, ?> ref, Caller caller) {
		return resolveMedia(ref, caller);
	}

	/**
	 * Recheck the same owner/asset authorization at render, export and channel
	 * boundaries.
	 */
	public Mono<com.grassland.intelligence.media.MediaReference> resolveMedia(Map<?, ?> ref, Caller caller) {
		UUID id;
		try {
			id = UUID.fromString((String) ref.get("id"));
		} catch (Exception error) {
			return Mono.error(new IntelligenceException(400, "结果引用 ID 无效"));
		}
		Mono<com.grassland.intelligence.media.MediaReference> accessible = "media".equals(ref.get("refType"))
				? media.findById(id)
						.filter(item -> caller.accountId().equals(item.ownerAccountId())
								&& item.status() == MediaStatus.ACTIVE && item.deletedAt() == null
								&& (item.expiresAt() == null || item.expiresAt().isAfter(Instant.now())))
				: assets.findForCreation(List.of(id), caller.accountId(), caller.organizationId()).next()
						.flatMap(asset -> media.findById(asset.mediaReferenceId())
								.filter(item -> item.status() == MediaStatus.ACTIVE && item.deletedAt() == null
										&& (item.expiresAt() == null || item.expiresAt().isAfter(Instant.now()))));
		return accessible.switchIfEmpty(
				Mono.error(new IntelligenceException(404, "RESULT_REFERENCE_UNAVAILABLE", "选定的结果不存在或不可用")));
	}

	private static List<Map<?, ?>> refs(Map<String, Object> workspace) {
		List<Map<?, ?>> result = new java.util.ArrayList<>();
		if (workspace.get("resultRefs") instanceof List<?> refs)
			refs.stream().filter(Map.class::isInstance).map(item -> (Map<?, ?>) item).forEach(result::add);
		if (workspace.get("delivery") instanceof Map<?, ?> delivery) {
			if (delivery.get("coverRef") instanceof Map<?, ?> cover)
				result.add(cover);
			if (delivery.get("mediaRefs") instanceof List<?> mediaRefs)
				mediaRefs.stream().filter(Map.class::isInstance).map(item -> (Map<?, ?>) item).forEach(result::add);
		}
		return result;
	}
}
