package com.grassland.intelligence.creationcanvas;

import com.grassland.intelligence.creationassistant.CreationDraft;
import com.grassland.intelligence.creationassistant.CreationDraftRepository;
import com.grassland.intelligence.creationassistant.CreationWorkspace;
import com.grassland.intelligence.creationassistant.DraftStatus;
import com.grassland.intelligence.creationassistant.DraftSourceType;
import com.grassland.intelligence.creationcontext.CreationContextSnapshotRepository;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.videoproduction.VideoStoryboard;
import com.grassland.intelligence.videoproduction.VideoStoryboardRepository;
import com.grassland.intelligence.videoproduction.VideoStoryboardVariantRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Shared parent locks. The caller owns the transaction, including every
 * resulting write.
 */
@Component
public class CanvasProjectAccess {
	public enum WriteKind {
		CONTENT, CANVAS, PLAN_CREATE, PLAN_APPLY, VARIANT, DELIVERY
	}
	public record ProjectKey(UUID draftId, UUID storyboardId) {
	}
	public record LockedProject(CreationDraft draft, VideoStoryboard storyboard,
			CreationCanvasRepository.CanvasRow canvas, boolean trustedSource) {
		public LockedProject(CreationDraft draft, VideoStoryboard storyboard,
				CreationCanvasRepository.CanvasRow canvas) {
			this(draft, storyboard, canvas, true);
		}
	}
	public record SourceEvidence(DraftSourceType type, String taskId, Integer taskVersion, String storeId,
			String organizationId, String platform, String contentForm) {
		public boolean matches(CreationDraft draft) {
			return type == draft.sourceType() && Objects.equals(taskId, draft.taskId())
					&& Objects.equals(taskVersion, draft.taskVersion()) && Objects.equals(storeId, draft.storeId())
					&& Objects.equals(organizationId, draft.organizationId())
					&& Objects.equals(platform, draft.platform()) && Objects.equals(contentForm, draft.contentForm());
		}
		static SourceEvidence from(CreationDraft draft) {
			return new SourceEvidence(draft.sourceType(), draft.taskId(), draft.taskVersion(), draft.storeId(),
					draft.organizationId(), draft.platform(), draft.contentForm());
		}
	}

	private final CreationDraftRepository drafts;
	private final VideoStoryboardRepository storyboards;
	private final CreationCanvasRepository canvases;
	private final VideoCanvasWorkspaceRepository bindings;
	private final VideoStoryboardVariantRepository variants;
	private final CreationContextSnapshotRepository snapshots;

	public CanvasProjectAccess(CreationDraftRepository drafts, VideoStoryboardRepository storyboards,
			CreationCanvasRepository canvases, VideoCanvasWorkspaceRepository bindings,
			VideoStoryboardVariantRepository variants, CreationContextSnapshotRepository snapshots) {
		this.drafts = drafts;
		this.storyboards = storyboards;
		this.canvases = canvases;
		this.bindings = bindings;
		this.variants = variants;
		this.snapshots = snapshots;
	}

	public Mono<List<LockedProject>> lockProjects(String accountId, List<ProjectKey> keys) {
		return lockProjects(accountId, keys, true);
	}

	/**
	 * Collect the whole lock set before taking any parent lock (a variant also
	 * reads its root).
	 */
	public Mono<List<ProjectKey>> keysWithRoot(String accountId, ProjectKey source) {
		return variants.findByStoryboard(source.storyboardId())
				.map(VideoStoryboardVariantRepository.Variant::rootStoryboardId).defaultIfEmpty(source.storyboardId())
				.flatMap(root -> root.equals(source.storyboardId())
						? Mono.just(List.of(source))
						: bindings.findByStoryboard(root).filter(binding -> accountId.equals(binding.accountId()))
								.switchIfEmpty(Mono.error(notFound()))
								.map(binding -> List.of(source, new ProjectKey(binding.draftId(), root))));
	}

	/**
	 * A new binding has no relation row yet; ownership and any existing relation
	 * still apply.
	 */
	public Mono<LockedProject> lockForBinding(String accountId, ProjectKey key) {
		return lockProjects(accountId, List.of(key), false).map(List::getFirst);
	}

	private Mono<List<LockedProject>> lockProjects(String accountId, List<ProjectKey> keys, boolean requireBinding) {
		return Mono.defer(() -> {
			Map<UUID, CreationDraft> lockedDrafts = new LinkedHashMap<>();
			Map<UUID, VideoStoryboard> lockedBoards = new LinkedHashMap<>();
			Map<UUID, CreationCanvasRepository.CanvasRow> lockedCanvases = new LinkedHashMap<>();
			var draftIds = keys.stream().map(ProjectKey::draftId).distinct().sorted().toList();
			var storyboardIds = keys.stream().map(ProjectKey::storyboardId).distinct().sorted().toList();
			// Do not zip these publishers: every transaction takes parents in this exact
			// order.
			return Flux.fromIterable(draftIds)
					.concatMap(id -> drafts.lockById(id)
							.filter(d -> accountId.equals(d.ownerAccountId()) && d.deletedAt() == null)
							.switchIfEmpty(Mono.error(notFound())).doOnNext(d -> lockedDrafts.put(id, d)))
					.thenMany(Flux.fromIterable(storyboardIds)
							.concatMap(id -> storyboards.lockById(id, accountId).switchIfEmpty(Mono.error(notFound()))
									.doOnNext(s -> lockedBoards.put(id, s))))
					.thenMany(Flux.fromIterable(draftIds)
							.concatMap(id -> canvases.lockByDraftId(id).filter(c -> accountId.equals(c.accountId()))
									.doOnNext(c -> lockedCanvases.put(id, c))))
					.thenMany(Flux.fromIterable(keys)
							.concatMap(key -> bindings.findByStoryboard(key.storyboardId()).map(binding -> {
								if (!accountId.equals(binding.accountId()))
									throw notFound();
								if (!key.draftId().equals(binding.draftId()))
									throw new IntelligenceException(409, "CANVAS_OPERATION_CONFLICT", "草稿与分镜不匹配");
								return true;
							}).defaultIfEmpty(!requireBinding).map(valid -> {
								if (!valid)
									throw new IntelligenceException(409, "CANVAS_OPERATION_CONFLICT", "项目尚未绑定");
								return new LockedProject(lockedDrafts.get(key.draftId()),
										lockedBoards.get(key.storyboardId()), lockedCanvases.get(key.draftId()));
							}).flatMap(
									project -> sourceEvidence(project.draft(), project.storyboard())
											.map(source -> new LockedProject(project.draft(), project.storyboard(),
													project.canvas(),
													source.matches(project.draft())
															&& documentIdentityMatches(project)))
											.onErrorResume(IntelligenceException.class,
													error -> error.status() == 404
															? Mono.error(error)
															: Mono.just(new LockedProject(project.draft(),
																	project.storyboard(), project.canvas(), false))))))
					.collectList();
		});
	}

	/**
	 * Legacy storyboard-only editing is allowed, but cannot bypass a concurrently
	 * created binding.
	 */
	public Mono<VideoStoryboard> lockContent(String accountId, UUID storyboardId) {
		return bindings.findByStoryboard(storyboardId)
				.flatMap(binding -> lockProjects(accountId, List.of(new ProjectKey(binding.draftId(), storyboardId)))
						.map(projects -> {
							var project = projects.getFirst();
							checkWritable(project, WriteKind.CONTENT);
							return project.storyboard();
						}))
				.switchIfEmpty(Mono.defer(() -> storyboards.lockById(storyboardId, accountId)
						.switchIfEmpty(Mono.error(notFound()))
						.flatMap(board -> bindings.findByStoryboard(storyboardId)
								.flatMap(binding -> Mono.<VideoStoryboard>error(
										new IntelligenceException(409, "CANVAS_VERSION_CONFLICT", "项目已建立关联，请重试编辑")))
								.switchIfEmpty(Mono.just(board)))));
	}

	public void checkWritable(LockedProject project, WriteKind kind) {
		if (project.draft().deletedAt() != null)
			throw notFound();
		if (project.draft().status() == DraftStatus.ARCHIVED)
			throw new IntelligenceException(409, "CANVAS_RESOURCE_LOCKED", "项目已归档，只能查看");
		CreationWorkspace.requireWritable(project.draft().workspace());
		if (!project.trustedSource())
			throw sourceConflict();
		if (kind == WriteKind.CONTENT && project.storyboard().isCommitted())
			throw new IntelligenceException(409, "CANVAS_RESOURCE_LOCKED", "分镜已提交成片，请派生新方案");
		if (kind != WriteKind.DELIVERY && kind != WriteKind.CONTENT && project.canvas() != null
				&& project.canvas().schemaVersion() != 1)
			throw new IntelligenceException(400, "CANVAS_SCHEMA_UNSUPPORTED", "画布版本暂不支持编辑");
	}

	/**
	 * Immutable source-version evidence. Current parent content is never
	 * substituted for a missing version.
	 */
	public Mono<SourceEvidence> sourceEvidence(CreationDraft draft, VideoStoryboard storyboard) {
		return variants.findByStoryboard(storyboard.id()).map(java.util.Optional::of)
				.defaultIfEmpty(java.util.Optional.empty()).flatMap(variant -> {
					Mono<SourceEvidence> base;
					if (variant.isPresent()) {
						var row = variant.get();
						if (!draft.ownerAccountId().equals(row.accountId()))
							return Mono.error(sourceConflict());
						base = bindings.findByStoryboard(row.parentStoryboardId())
								.filter(binding -> draft.ownerAccountId().equals(binding.accountId()))
								.flatMap(binding -> drafts.findById(binding.draftId()))
								.filter(parent -> draft.ownerAccountId().equals(parent.ownerAccountId())
										&& parent.deletedAt() == null)
								.flatMap(parent -> drafts.findVersion(parent.id(), row.sourceDraftVersion())
										.map(version -> new SourceEvidence(version.sourceType(), version.taskId(),
												version.taskVersion(), version.storeId(), parent.organizationId(),
												version.platform(), version.contentForm())))
								.switchIfEmpty(Mono.error(sourceConflict()));
					} else
						base = Mono.just(SourceEvidence.from(draft));
					return base.flatMap(origin -> {
						if (storyboard.contextSnapshotId() == null) {
							if (origin.type() == DraftSourceType.TASK)
								return Mono.error(sourceConflict());
							return Mono.just(new SourceEvidence(origin.type(), origin.taskId(), origin.taskVersion(),
									origin.storeId(), origin.organizationId(), draft.platform(), draft.contentForm()));
						}
						return snapshots.findById(storyboard.contextSnapshotId())
								.filter(snapshot -> draft.ownerAccountId().equals(snapshot.accountId()))
								.switchIfEmpty(Mono.error(notFound())).map(snapshot -> {
									if (!Objects.equals(storyboard.organizationId(), snapshot.organizationId())
											|| origin.type() == DraftSourceType.STORE
											|| (origin.taskId() != null
													&& !Objects.equals(origin.taskId(), snapshot.taskId()))
											|| (origin.taskVersion() != null
													&& !Objects.equals(origin.taskVersion(), snapshot.taskVersion())))
										throw sourceConflict();
									String storeId = origin.storeId();
									if (variant.isEmpty() && storeId == null
											&& snapshot.taskSnapshot().get("storeId") instanceof String store)
										storeId = store;
									return new SourceEvidence(DraftSourceType.TASK, snapshot.taskId(),
											snapshot.taskVersion(), storeId, snapshot.organizationId(),
											snapshot.platformId(), snapshot.contentFormId());
								});
					});
				});
	}

	private static boolean documentIdentityMatches(LockedProject project) {
		if (project.canvas() == null || project.canvas().schemaVersion() != 1)
			return true;
		try {
			var id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(project.canvas().documentJson())
					.path("storyboardId");
			return !id.isTextual() || project.storyboard().id().toString().equals(id.asText());
		} catch (Exception error) {
			return false;
		}
	}

	public static IntelligenceException sourceConflict() {
		return new IntelligenceException(409, "CANVAS_OPERATION_CONFLICT", "创作来源无法确认，请从原方案重新派生");
	}

	private static IntelligenceException notFound() {
		return new IntelligenceException(404, "CANVAS_RESOURCE_NOT_FOUND", "项目不存在");
	}
}
