package com.grassland.intelligence.creationstudio.render;

import com.grassland.intelligence.creationassistant.CreationDraft;
import com.grassland.intelligence.creationassistant.CreationDraftRepository;
import com.grassland.intelligence.creationstudio.plan.PlanJson;
import com.grassland.intelligence.creationstudio.source.SourceDocument;
import com.grassland.intelligence.creationstudio.source.SourceDocumentRepository;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-16（API101-18 §6.7）：确定性排版预览。 纯计算（零模型调用、不落 AI
 * run）：按请求版本读取草稿（当前版或缺省）， 从工作区已采用引用装配绑定媒体（placement.afterBlockId → 源块文本锚定），经
 * {@link CreationDocumentRenderer} 输出受控 HTML/文本；缺媒体/未绑定只警告不伪装完整。
 */
@Service
public class CreationRenderService {

	static final String RENDER_VERSION = CreationDocumentRenderer.RENDER_VERSION;

	private final CreationDraftRepository drafts;
	private final SourceDocumentRepository sources;

	public CreationRenderService(CreationDraftRepository drafts, SourceDocumentRepository sources) {
		this.drafts = drafts;
		this.sources = sources;
	}

	public record PreviewCommand(UUID draftId, int version, String theme, boolean includeTitle,
			boolean citeExternalLinks) {
	}

	public record RenderOutcome(Map<String, Object> preview) {
	}

	public Mono<RenderOutcome> preview(Caller caller, PreviewCommand command) {
		return loadVersion(command, caller).flatMap(draft -> {
			Map<String, Object> workspace = draft.workspace() == null ? Map.of() : draft.workspace();
			Mono<Map<String, String>> blockTexts = loadSourceBlockTexts(workspace, draft.ownerAccountId());
			return blockTexts.map(blockMap -> render(draft, blockMap, command));
		});
	}

	/** 当前版直接读；历史版走版本快照（不可变，正文以快照为准）。 */
	private Mono<CreationDraft> loadVersion(PreviewCommand command, Caller caller) {
		Mono<CreationDraft> base = drafts.findById(command.draftId())
				.filter(draft -> caller.accountId().equals(draft.ownerAccountId()) && draft.deletedAt() == null)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "草稿不存在")));
		return base.flatMap(current -> {
			if (command.version() == current.version()) {
				return Mono.just(current);
			}
			if (command.version() < 1 || command.version() > current.version()) {
				return Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "草稿版本不存在"));
			}
			return drafts.findVersion(command.draftId(), command.version())
					.map(version -> new CreationDraft(version.draftId(), current.ownerAccountId(),
							current.organizationId(), version.title(), version.sourceType(), version.taskId(),
							version.taskVersion(), version.storeId(), version.platform(), version.contentForm(),
							version.topic(), version.articleTitle(), version.outline(), version.content(),
							version.contentMode(), version.questionText(), version.questionRef(), version.status(),
							version.version(), null, version.createdAt(), null, version.workspace(),
							version.resultAssetIds(), version.runIds()));
		});
	}

	private Mono<Map<String, String>> loadSourceBlockTexts(Map<String, Object> workspace, String ownerId) {
		String sourceId = workspace.get("inputs") instanceof Map<?, ?> inputs
				&& inputs.get("studio") instanceof Map<?, ?> studio
				&& studio.get("sourceDocumentId") instanceof String id ? id : null;
		if (sourceId == null) {
			return Mono.just(Map.of());
		}
		return sources.findById(UUID.fromString(sourceId)).filter(document -> ownerId.equals(document.ownerAccountId()))
				.map(document -> {
					Map<String, String> texts = new LinkedHashMap<>();
					for (SourceDocument.Block block : document.blocks()) {
						texts.put(block.id(), block.text());
					}
					return texts;
				}).onErrorResume(error -> Mono.just(Map.of())).defaultIfEmpty(Map.of());
	}

	@SuppressWarnings("unchecked")
	private RenderOutcome render(CreationDraft draft, Map<String, String> blockTexts, PreviewCommand command) {
		Map<String, Object> workspace = draft.workspace() == null ? Map.of() : draft.workspace();
		List<CreationDocumentRenderer.BoundMedia> media = new ArrayList<>();
		Map<String, Object> delivery = workspace.get("delivery") instanceof Map<?, ?> deliveryMap
				? (Map<String, Object>) deliveryMap
				: Map.of();
		// 封面（coverRef）
		if (delivery.get("coverRef") instanceof Map<?, ?> coverRef && coverRef.get("id") instanceof String coverId) {
			media.add(new CreationDocumentRenderer.BoundMedia(coverId, "封面", null, true, 0));
		}
		// 正文插图（resultRefs 带 placement 的绑定；其余按未定位附后）
		if (workspace.get("resultRefs") instanceof List<?> refs) {
			int position = 0;
			for (Object refObject : refs) {
				if (!(refObject instanceof Map<?, ?> ref) || !(ref.get("id") instanceof String mediaId)) {
					continue;
				}
				position++;
				String afterBlockId = ref.get("placement") instanceof Map<?, ?> placement
						&& placement.get("afterBlockId") instanceof String after ? after : null;
				String afterText = afterBlockId == null ? null : blockTexts.get(afterBlockId);
				String caption = ref.get("cardId") instanceof String cardId
						? "配图 " + cardId.substring(0, Math.min(8, cardId.length()))
						: "配图";
				media.add(new CreationDocumentRenderer.BoundMedia(mediaId, caption, afterText, false, position));
			}
		}
		CreationRenderTheme theme = CreationRenderTheme.of(command.theme());
		String content = draft.content() == null ? "" : draft.content();
		CreationDocumentRenderer.Rendered rendered = CreationDocumentRenderer.render(content,
				draft.articleTitle() == null ? draft.title() : draft.articleTitle(), command.includeTitle(),
				command.citeExternalLinks(), theme, media);
		List<String> unresolved = media.stream().filter(item -> !item.cover() && item.afterText() == null)
				.map(CreationDocumentRenderer.BoundMedia::mediaId).toList();
		Map<String, Object> preview = new LinkedHashMap<>();
		preview.put("draftId", draft.id().toString());
		preview.put("version", draft.version());
		preview.put("renderVersion", RENDER_VERSION);
		preview.put("contentHash", PlanJson.sha256(content));
		preview.put("html", rendered.html());
		preview.put("text", rendered.text());
		preview.put("warnings", rendered.warnings());
		preview.put("unresolvedMediaIds", unresolved);
		return new RenderOutcome(preview);
	}
}
