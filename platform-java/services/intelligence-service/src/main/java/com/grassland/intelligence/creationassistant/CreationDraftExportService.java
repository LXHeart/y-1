package com.grassland.intelligence.creationassistant;

import com.grassland.intelligence.contentlibrary.ContentAssetRepository;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 图文导出（AI内容中心改造-02 / 总方案 §8.6）：{@code POST /api/creation-drafts/{id}/exports}。
 *
 * <p>
 * 指定草稿版本导出（T32：不混用新旧版本）；所有 {@code resultRefs} 经归属/可用性校验（复用
 * {@link CreationResultReferences}），媒体返回对象存储 presigned 短期下载链接（过期重新请求，
 * 不写入 workspace、不触发生成）。manifest 携带交付契约（标题/正文/话题/摘要/声明）。
 */
@Component
public class CreationDraftExportService {

	static final long DOWNLOAD_TTL_SECONDS = 900;
	private static final java.util.Set<String> FORMATS = java.util.Set.of("bundle-manifest");

	/** 导出数据源：当前行或历史版本快照的统一只读视图。 */
	record ExportTarget(String title, String topic, String articleTitle, String outline, String content,
			String platform, String contentForm, String contentMode, String questionText, Map<String, Object> workspace,
			int version) {
	}

	private final CreationDraftRepository drafts;
	private final MediaReferenceRepository media;
	private final ContentAssetRepository assets;
	private final ObjectProvider<ObjectStorageAdapter> storageProvider;

	public CreationDraftExportService(CreationDraftRepository drafts,
			MediaReferenceRepository media, ContentAssetRepository assets,
			ObjectProvider<ObjectStorageAdapter> storageProvider) {
		this.drafts = drafts;
		this.media = media;
		this.assets = assets;
		this.storageProvider = storageProvider;
	}

	public Mono<Map<String, Object>> export(CreationDraft draft, Integer requestedVersion, String format, Caller caller) {
		if (format != null && !format.isBlank() && !FORMATS.contains(format)) {
			return Mono.error(new IntelligenceException(400, "不支持的导出格式"));
		}
		Mono<ExportTarget> target = requestedVersion == null
				? Mono.just(fromDraft(draft))
				: drafts.findVersion(draft.id(), requestedVersion)
						.switchIfEmpty(Mono.error(new IntelligenceException(404, "草稿版本不存在")))
						.map(version -> new ExportTarget(version.title(), version.topic(), version.articleTitle(),
								version.outline(), version.content(), version.platform(), version.contentForm(),
								version.contentMode() == null ? "article" : version.contentMode().db(),
								version.questionText(), version.workspace(), version.version()));
		return target.flatMap(exportTarget -> downloadItems(exportTarget.workspace(), caller).collectList()
				.map(items -> render(exportTarget, draft.id().toString(), items)));
	}

	private ExportTarget fromDraft(CreationDraft draft) {
		return new ExportTarget(draft.title(), draft.topic(), draft.articleTitle(), draft.outline(), draft.content(),
				draft.platform(), draft.contentForm(),
				draft.contentMode() == null ? "article" : draft.contentMode().db(),
				draft.questionText(), draft.workspace(), draft.version());
	}

	private Map<String, Object> render(ExportTarget target, String draftId, List<Map<String, Object>> downloads) {
		Map<String, Object> manifest = new LinkedHashMap<>();
		manifest.put("draftId", draftId);
		manifest.put("version", target.version());
		manifest.put("exportedAt", Instant.now().toString());
		manifest.put("platform", target.platform());
		manifest.put("contentForm", target.contentForm());
		manifest.put("contentMode", target.contentMode());
		manifest.put("title", target.title());
		if (target.questionText() != null) manifest.put("questionText", target.questionText());
		if (target.articleTitle() != null) manifest.put("articleTitle", target.articleTitle());
		if (target.topic() != null) manifest.put("topic", target.topic());
		if (target.outline() != null && !target.outline().isBlank()) manifest.put("outline", target.outline());
		if (target.content() != null && !target.content().isBlank()) manifest.put("content", target.content());
		if (target.workspace().get("delivery") instanceof Map<?, ?> delivery) {
			manifest.put("delivery", delivery);
		}
		Object brief = target.workspace().get("brief");
		if (brief == null && target.workspace().get("inputs") instanceof Map<?, ?> inputs) {
			brief = inputs.get("brief");
		}
		if (brief instanceof Map<?, ?> briefMap && !briefMap.isEmpty()) {
			Map<String, Object> sources = new LinkedHashMap<>();
			if (briefMap.get("sourceRefs") != null) sources.put("sourceRefs", briefMap.get("sourceRefs"));
			if (briefMap.get("facts") != null) sources.put("facts", briefMap.get("facts"));
			if (!sources.isEmpty()) manifest.put("sources", sources);
		}

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("draftId", draftId);
		response.put("version", target.version());
		response.put("expiresAt", Instant.now().plusSeconds(DOWNLOAD_TTL_SECONDS).toString());
		response.put("manifest", manifest);
		response.put("downloads", downloads);
		return response;
	}

	/** 按工作区 resultRefs 顺序生成授权下载项；presign 无 I/O 仅签名，可在 map 内调用。 */
	private Flux<Map<String, Object>> downloadItems(Map<String, Object> workspace, Caller caller) {
		ObjectStorageAdapter storage = storageProvider.getIfAvailable();
		if (!(workspace.get("resultRefs") instanceof List<?> refs)) return Flux.empty();
		List<Map<?, ?>> refMaps = new ArrayList<>();
		for (Object ref : refs) {
			if (ref instanceof Map<?, ?> refMap) refMaps.add(refMap);
		}
		return Flux.fromIterable(refMaps).index().concatMap(indexed -> {
			Map<?, ?> ref = indexed.getT2();
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("refType", ref.get("refType"));
			item.put("id", ref.get("id"));
			item.put("role", ref.get("role"));
			item.put("cardId", ref.get("cardId"));
			item.put("position", ref.get("position") == null ? indexed.getT1() + 1 : ref.get("position"));
			return resolveMedia(ref, caller).map(reference -> {
				item.put("contentType", reference.mimeType());
				item.put("sizeBytes", reference.sizeBytes());
				if (storage != null) {
					item.put("url", storage.presignDownload(reference.objectKey(), DOWNLOAD_TTL_SECONDS).toString());
				} else {
					item.put("unavailable", "storage");
				}
				return item;
			}).defaultIfEmpty(mergeUnavailable(item));
		});
	}

	private static Map<String, Object> mergeUnavailable(Map<String, Object> item) {
		item.put("unavailable", "expired");
		return item;
	}

	/** 归属不符（IDOR）硬失败；媒体缺失/过期按项标记 unavailable（T31：明确标记缺项）。 */
	private Mono<MediaReference> resolveMedia(Map<?, ?> ref, Caller caller) {
		UUID id;
		try {
			id = UUID.fromString(String.valueOf(ref.get("id")));
		} catch (Exception error) {
			return Mono.error(new IntelligenceException(400, "结果引用 ID 无效"));
		}
		if ("media".equals(ref.get("refType"))) {
			return media.findById(id).flatMap(item -> {
				if (!caller.accountId().equals(item.ownerAccountId())) {
					return Mono.error(new IntelligenceException(404, "RESULT_REFERENCE_UNAVAILABLE", "选定的结果不存在或不可用"));
				}
				// 已删除/过期媒体按项标记缺项（T31），不阻断整包导出。
				return item.deletedAt() == null ? Mono.just(item) : Mono.empty();
			});
		}
		return assets.findForCreation(List.of(id), caller.accountId(), caller.organizationId()).next()
				.flatMap(asset -> media.findById(asset.mediaReferenceId()));
	}
}
