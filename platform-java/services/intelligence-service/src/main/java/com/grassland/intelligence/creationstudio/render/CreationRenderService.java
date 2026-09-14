package com.grassland.intelligence.creationstudio.render;

import com.grassland.intelligence.creationassistant.CreationDraft;
import com.grassland.intelligence.creationassistant.CreationDraftRepository;
import com.grassland.intelligence.creationassistant.CreationResultReferences;
import com.grassland.intelligence.creationstudio.CreationStudioContextService;
import com.grassland.intelligence.creationstudio.plan.PlanJson;
import com.grassland.intelligence.creationstudio.source.SourceDocument;
import com.grassland.intelligence.creationstudio.source.SourceDocumentRepository;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class CreationRenderService {
	private final CreationDraftRepository drafts;
	private final SourceDocumentRepository sources;
	private final CreationResultReferences references;
	private final ObjectProvider<ObjectStorageAdapter> storageProvider;
	private final CreationImageProcessor worker;

	public CreationRenderService(CreationDraftRepository drafts, SourceDocumentRepository sources,
			CreationResultReferences references, ObjectProvider<ObjectStorageAdapter> storageProvider,
			CreationImageProcessor worker) {
		this.drafts = drafts;
		this.sources = sources;
		this.references = references;
		this.storageProvider = storageProvider;
		this.worker = worker;
	}
	public record PreviewCommand(UUID draftId, int version, String theme, boolean includeTitle,
			boolean citeExternalLinks) {
	}
	public record RenderOutcome(Map<String, Object> preview) {
	}
	public record ResolvedMedia(Map<String, Object> ref, MediaReference media, Integer afterBlockPosition,
			boolean stale) {
	}
	public record PreparedDocument(CreationDraft draft, List<ResolvedMedia> media, List<String> unavailable,
			List<SourceDocument> sources) {
	}

	public Mono<RenderOutcome> preview(Caller caller, PreviewCommand command) {
		return prepare(caller, command.draftId(), command.version()).flatMap(prepared -> worker.bounded(() -> {
			var rendered = renderPrepared(prepared, command.theme(), command.includeTitle(),
					command.citeExternalLinks());
			var html = org.jsoup.Jsoup.parseBodyFragment(rendered.html());
			html.outputSettings().prettyPrint(false);
			List<String> unresolved = new ArrayList<>(prepared.unavailable());
			unresolved.addAll(rendered.unresolvedMediaIds());
			ObjectStorageAdapter storage = storageProvider.getIfAvailable();
			Map<String, MediaReference> byId = new LinkedHashMap<>();
			prepared.media().forEach(item -> byId.put(item.media().id().toString(), item.media()));
			for (var image : html.select("img[data-media-id]")) {
				MediaReference media = byId.get(image.attr("data-media-id"));
				if (storage == null || media == null) {
					unresolved.add(image.attr("data-media-id"));
					image.removeAttr("src");
				} else {
					image.attr("src", storage.presignDownload(media.objectKey(), 900).toString());
				}
			}
			Map<String, Object> preview = new LinkedHashMap<>();
			preview.put("draftId", prepared.draft().id().toString());
			preview.put("version", prepared.draft().version());
			preview.put("renderVersion", CreationDocumentRenderer.RENDER_VERSION);
			preview.put("contentHash", PlanJson.sha256(content(prepared.draft())));
			preview.put("html", html.body().html());
			preview.put("text", rendered.text());
			preview.put("warnings", rendered.warnings());
			preview.put("unresolvedMediaIds", unresolved.stream().distinct().toList());
			return new RenderOutcome(preview);
		}));
	}

	public Mono<PreparedDocument> prepare(Caller caller, UUID draftId, int version) {
		return loadSnapshot(caller, draftId, version).flatMap(draft -> {
			List<Map<String, Object>> refs = mediaRefs(draft);
			if (refs.size() > 20)
				return Mono.error(new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", "最多交付 20 个媒体项"));
			List<String> unavailable = new ArrayList<>();
			Map<UUID, SourceDocument> documents = new LinkedHashMap<>();
			return Flux.fromIterable(refs)
					.concatMap(ref -> references.resolveMedia(ref, caller)
							.flatMap(media -> resolvePosition(draft, ref, media, documents))
							.onErrorResume(IntelligenceException.class, failure -> {
								unavailable.add(String.valueOf(ref.get("id")));
								return Mono.empty();
							}))
					.collectList()
					.flatMap(media -> loadOriginalSource(draft).doOnNext(source -> documents.put(source.id(), source))
							.then(Mono.fromSupplier(() -> new PreparedDocument(draft, media, unavailable,
									List.copyOf(documents.values())))));
		});
	}

	Mono<CreationDraft> loadSnapshot(Caller caller, UUID draftId, int version) {
		return drafts.findById(draftId)
				.filter(draft -> caller.accountId().equals(draft.ownerAccountId()) && draft.deletedAt() == null)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "草稿不存在")))
				.flatMap(current -> {
					Mono<CreationDraft> selected;
					if (version == current.version())
						selected = Mono.just(current);
					else if (version < 1 || version > current.version())
						selected = Mono.empty();
					else
						selected = drafts.findVersion(draftId, version).map(row -> new CreationDraft(row.draftId(),
								current.ownerAccountId(), current.organizationId(), row.title(), row.sourceType(),
								row.taskId(), row.taskVersion(), row.storeId(), row.platform(), row.contentForm(),
								row.topic(), row.articleTitle(), row.outline(), row.content(), row.contentMode(),
								row.questionText(), row.questionRef(), row.status(), row.version(), null,
								row.createdAt(), null, row.workspace(), row.resultAssetIds(), row.runIds()));
					return selected
							.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "草稿版本不存在")))
							.doOnNext(CreationStudioContextService::requireStudioScope);
				});
	}

	private Mono<ResolvedMedia> resolvePosition(CreationDraft draft, Map<String, Object> ref, MediaReference media,
			Map<UUID, SourceDocument> documents) {
		String after = ref.get("placement") instanceof Map<?, ?> placement
				&& placement.get("afterBlockId") instanceof String id ? id : null;
		if (after == null)
			return Mono.just(new ResolvedMedia(ref, media, null, false));
		return sources.findByBlockId(draft.ownerAccountId(), draft.id(), after).map(source -> {
			documents.put(source.id(), source);
			Integer position = source.blocks().stream().filter(block -> block.id().equals(after))
					.map(SourceDocument.Block::position).findFirst().orElse(null);
			boolean stale = !source.normalizedMarkdown().equals(content(draft)) || position == null;
			return new ResolvedMedia(ref, media, position, stale);
		}).defaultIfEmpty(new ResolvedMedia(ref, media, null, true));
	}

	private Mono<SourceDocument> loadOriginalSource(CreationDraft draft) {
		Object raw = draft.workspace() == null ? null : draft.workspace().get("inputs");
		if (!(raw instanceof Map<?, ?> inputs) || !(inputs.get("studio") instanceof Map<?, ?> studio)
				|| !(studio.get("sourceDocumentId") instanceof String id))
			return Mono.empty();
		return sources.findById(UUID.fromString(id))
				.filter(source -> source.ownerAccountId().equals(draft.ownerAccountId())
						&& source.draftId().equals(draft.id()));
	}

	static CreationDocumentRenderer.Rendered renderPrepared(PreparedDocument prepared, String theme,
			boolean includeTitle, boolean citeExternalLinks) {
		List<CreationDocumentRenderer.BoundMedia> media = new ArrayList<>();
		int position = 0;
		for (ResolvedMedia item : prepared.media()) {
			position++;
			boolean cover = "cover".equals(item.ref().get("role"));
			media.add(new CreationDocumentRenderer.BoundMedia(item.media().id().toString(),
					cover ? "封面" : "配图 " + position, null, cover, position, item.afterBlockPosition(), item.stale()));
		}
		return CreationDocumentRenderer.render(content(prepared.draft()), title(prepared.draft()), includeTitle,
				citeExternalLinks, CreationRenderTheme.of(theme), media);
	}

	@SuppressWarnings("unchecked")
	static List<Map<String, Object>> mediaRefs(CreationDraft draft) {
		Map<String, Object> workspace = draft.workspace() == null ? Map.of() : draft.workspace();
		Map<?, ?> delivery = workspace.get("delivery") instanceof Map<?, ?> map ? map : Map.of();
		Map<String, Map<String, Object>> refs = new LinkedHashMap<>();
		if (delivery.get("coverRef") instanceof Map<?, ?> cover) {
			Map<String, Object> value = new LinkedHashMap<>((Map<String, Object>) cover);
			value.put("role", "cover");
			refs.put(refKey(value), value);
		}
		for (Object collection : new Object[]{workspace.get("resultRefs"), delivery.get("mediaRefs")}) {
			if (!(collection instanceof List<?> list))
				continue;
			for (Object raw : list)
				if (raw instanceof Map<?, ?> ref && ref.get("id") instanceof String) {
					Map<String, Object> value = new LinkedHashMap<>((Map<String, Object>) ref);
					refs.putIfAbsent(refKey(value), value);
				}
		}
		return List.copyOf(refs.values());
	}
	private static String refKey(Map<String, Object> ref) {
		return ref.get("refType") + ":" + ref.get("id");
	}
	static String content(CreationDraft draft) {
		return draft.content() == null ? "" : draft.content().replace("\r\n", "\n").replace("\r", "\n");
	}
	static String title(CreationDraft draft) {
		return draft.articleTitle() == null || draft.articleTitle().isBlank() ? draft.title() : draft.articleTitle();
	}
}
