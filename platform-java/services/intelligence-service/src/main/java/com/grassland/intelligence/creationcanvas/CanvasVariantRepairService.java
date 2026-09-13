package com.grassland.intelligence.creationcanvas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.grassland.intelligence.creationassistant.CreationDraftRepository;
import com.grassland.intelligence.creationassistant.CreationWorkspace;
import com.grassland.intelligence.creationassistant.DraftStatus;
import com.grassland.intelligence.videoproduction.VideoStoryboardVariantRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Explicit workspace POST repair only. The caller's transaction owns every lock
 * and history write.
 */
@Service
public class CanvasVariantRepairService {
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CanvasVariantRepairService.class);
	private final CanvasProjectAccess projects;
	private final VideoCanvasWorkspaceRepository bindings;
	private final VideoStoryboardVariantRepository variants;
	private final CreationDraftRepository drafts;
	private final CreationCanvasRepository canvases;

	public CanvasVariantRepairService(CanvasProjectAccess projects, VideoCanvasWorkspaceRepository bindings,
			VideoStoryboardVariantRepository variants, CreationDraftRepository drafts,
			CreationCanvasRepository canvases) {
		this.projects = projects;
		this.bindings = bindings;
		this.variants = variants;
		this.drafts = drafts;
		this.canvases = canvases;
	}

	public Mono<Boolean> repairIfProvable(String accountId, UUID storyboardId) {
		return variants.findByStoryboard(storyboardId).map(Optional::of).defaultIfEmpty(Optional.empty())
				.flatMap(variant -> {
					List<UUID> boards = new ArrayList<>(List.of(storyboardId));
					variant.ifPresent(row -> {
						boards.add(row.parentStoryboardId());
						boards.add(row.rootStoryboardId());
					});
					return Flux.fromIterable(boards.stream().distinct().toList())
							.concatMap(id -> bindings.findByStoryboard(id)
									.filter(binding -> accountId.equals(binding.accountId()))
									.switchIfEmpty(Mono.error(CanvasProjectAccess.sourceConflict()))
									.map(binding -> new CanvasProjectAccess.ProjectKey(binding.draftId(), id)))
							.collectList().flatMap(keys -> projects.lockProjects(accountId, keys))
							.flatMap(locked -> repair(locked.getFirst(), variant.orElse(null), accountId));
				});
	}

	private Mono<Boolean> repair(CanvasProjectAccess.LockedProject project,
			VideoStoryboardVariantRepository.Variant variant, String accountId) {
		var draft = project.draft();
		var canvas = project.canvas();
		if (draft.status() == DraftStatus.ARCHIVED || (canvas != null && canvas.schemaVersion() != 1))
			return Mono.just(false);
		CreationWorkspace.requireWritable(draft.workspace());
		return projects.sourceEvidence(draft, project.storyboard()).flatMap(source -> {
			Optional<String> document = repairedDocument(project, variant);
			boolean sourceChanged = !source.matches(draft);
			if (!sourceChanged && document.isEmpty())
				return Mono.just(false);
			Mono<Void> sourceWrite = sourceChanged
					? drafts.appendVersion(draft, accountId)
							.then(drafts
									.repairSource(draft.id(), draft.version(), source.type(), source.taskId(),
											source.taskVersion(), source.storeId(), source.organizationId(),
											source.platform(), source.contentForm())
									.switchIfEmpty(Mono.error(CanvasProjectAccess.sourceConflict())))
							.then()
					: Mono.empty();
			Mono<Void> documentWrite = document.isPresent()
					? validateCanonical(document.get(), draft.id(), project.storyboard().id())
							.then(canvases.casUpdate(draft.id(), canvas.revision(), document.get())
									.switchIfEmpty(Mono.error(CanvasProjectAccess.sourceConflict())))
							.then()
					: Mono.empty();
			return sourceWrite.then(documentWrite).thenReturn(true).doOnSuccess(changed -> LOG.info(
					"canvas repair storyboard={} sourceChanged={} canvasChanged={} draftVersion={} canvasRevision={}",
					project.storyboard().id(), sourceChanged, document.isPresent(), draft.version(),
					canvas == null ? 0 : canvas.revision()));
		});
	}

	private Optional<String> repairedDocument(CanvasProjectAccess.LockedProject project,
			VideoStoryboardVariantRepository.Variant variant) {
		if (project.canvas() == null)
			return Optional.empty();
		try {
			var root = MAPPER.readTree(project.canvas().documentJson());
			if (!(root instanceof ObjectNode document))
				throw CanvasProjectAccess.sourceConflict();
			String id = document.path("storyboardId").asText();
			if (project.storyboard().id().toString().equals(id))
				return Optional.empty();
			if (variant == null || !variant.parentStoryboardId().toString().equals(id))
				throw CanvasProjectAccess.sourceConflict();
			document.put("storyboardId", project.storyboard().id().toString());
			return Optional.of(MAPPER.writeValueAsString(document));
		} catch (Exception error) {
			throw CanvasProjectAccess.sourceConflict();
		}
	}

	private Mono<Void> validateCanonical(String document, UUID draftId, UUID storyboardId) {
		List<String[]> refs = new ArrayList<>();
		CreationCanvasDocument.validateBody(document, draftId.toString(), refs);
		return (refs.isEmpty() ? Mono.just(false) : canvases.anyCrossProjectReference(refs, storyboardId.toString()))
				.flatMap(invalid -> invalid ? Mono.error(CanvasProjectAccess.sourceConflict()) : Mono.empty());
	}
}
