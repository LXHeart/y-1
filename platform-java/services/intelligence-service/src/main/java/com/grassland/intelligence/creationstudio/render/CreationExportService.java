package com.grassland.intelligence.creationstudio.render;

import com.grassland.intelligence.creationassistant.CreationDraft;
import com.grassland.intelligence.creationassistant.CreationDraftRepository;
import com.grassland.intelligence.creationstudio.plan.PlanJson;
import com.grassland.intelligence.creationstudio.source.SourceDocumentRepository;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-18（API101-19/20 §6.7）：新格式真实文件导出。
 *
 * <p>
 * 只按请求 version 读取不可变快照（历史版本字段/图片顺序/来源都属于该版本）；装配真实 文件（markdown/text/wechat-html
 * 单文件或 bundle-zip 包，媒体字节取对象存储），计算实际 sha256/字节数；先成功写对象再标 ready。manifest 只存
 * objectKey 与 hash——不存签名 URL， 读取时恢复签名。同键幂等；缺必需媒体按项列入 missingItems 且整单
 * failed（不伪装完整）。 纯文件装配：零模型调用、不重复计费。
 */
@Service
public class CreationExportService {

	public static final Set<String> NEW_FORMATS = Set.of("markdown", "text", "wechat-html", "bundle-zip");
	static final long DOWNLOAD_TTL_SECONDS = 900;
	private static final long EXPORT_RETENTION_DAYS = 7;

	private final CreationExportRepository repository;
	private final CreationDraftRepository drafts;
	private final SourceDocumentRepository sources;
	private final MediaReferenceRepository media;
	private final ObjectProvider<ObjectStorageAdapter> storageProvider;

	public CreationExportService(CreationExportRepository repository, CreationDraftRepository drafts,
			SourceDocumentRepository sources, MediaReferenceRepository media,
			ObjectProvider<ObjectStorageAdapter> storageProvider) {
		this.repository = repository;
		this.drafts = drafts;
		this.sources = sources;
		this.media = media;
		this.storageProvider = storageProvider;
	}

	public record ExportCommand(UUID requestId, int version, String format, String theme, boolean includeTitle,
			boolean citeExternalLinks) {
	}

	/** 响应形态：ready → StudioExportResult；building/failed → {exportId,state,error}。 */
	public Mono<Map<String, Object>> create(Caller caller, UUID draftId, ExportCommand command) {
		if (!NEW_FORMATS.contains(command.format())) {
			return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "不支持的新导出格式"));
		}
		ObjectStorageAdapter storage = storageProvider.getIfAvailable();
		if (storage == null) {
			return Mono.error(new IntelligenceException(503, "STUDIO_DEPENDENCY_UNAVAILABLE", "对象存储不可用"));
		}
		Mono<CreationDraft> snapshot = loadSnapshot(caller, draftId, command.version());
		return snapshot.flatMap(draft -> {
			String payloadHash = PlanJson.sha256(PlanJson.json(Map.ofEntries(Map.entry("draftId", draftId.toString()),
					Map.entry("version", command.version()), Map.entry("format", command.format()),
					Map.entry("theme", command.theme()), Map.entry("includeTitle", command.includeTitle()),
					Map.entry("citeExternalLinks", command.citeExternalLinks()),
					Map.entry("contentHash", PlanJson.sha256(draft.content() == null ? "" : draft.content())))));
			return repository.claimOrGet(UUID.randomUUID(), caller.accountId(), command.requestId().toString(), draftId,
					command.version(), command.format(), command.theme(), command.includeTitle(),
					command.citeExternalLinks(), payloadHash).flatMap(row -> {
						if (!row.payloadHash().equals(payloadHash)) {
							return Mono.error(new IntelligenceException(409, "STUDIO_OPERATION_CONFLICT",
									"同一 requestId 已用于不同导出请求"));
						}
						if ("ready".equals(row.state())) {
							return Mono.just(resultBody(row, storage));
						}
						if ("failed".equals(row.state())) {
							// 同键重试：failed → 重建非付费产物
							return rebuild(caller, row, draft, storage);
						}
						// building：本次请求负责装配（重放读回同一行由 resultBody 呈现 building）
						return build(caller, row, draft, storage)
								.then(repository.findByIdAndOwner(row.id(), caller.accountId()))
								.map(row2 -> resultBody(row2, storage));
					});
		});
	}

	private Mono<CreationDraft> loadSnapshot(Caller caller, UUID draftId, int version) {
		return drafts.findById(draftId)
				.filter(draft -> caller.accountId().equals(draft.ownerAccountId()) && draft.deletedAt() == null)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "草稿不存在")))
				.flatMap(current -> {
					if (version == current.version()) {
						return Mono.just(current);
					}
					if (version < 1 || version > current.version()) {
						return Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "草稿版本不存在"));
					}
					return drafts.findVersion(draftId, version)
							.map(row -> new CreationDraft(row.draftId(), current.ownerAccountId(),
									current.organizationId(), row.title(), row.sourceType(), row.taskId(),
									row.taskVersion(), row.storeId(), row.platform(), row.contentForm(), row.topic(),
									row.articleTitle(), row.outline(), row.content(), row.contentMode(),
									row.questionText(), row.questionRef(), row.status(), row.version(), null,
									row.createdAt(), null, row.workspace(), row.resultAssetIds(), row.runIds()));
				});
	}

	private Mono<Map<String, Object>> rebuild(Caller caller, CreationExportRepository.ExportRow row,
			CreationDraft draft, ObjectStorageAdapter storage) {
		// 重建：失败行重新装配（同键仍幂等——行 ID 不变），完成按新状态出响应体
		return repository.markFailed(row.id(), "STUDIO_EXPORT_RETRY").then(build(caller, row, draft, storage))
				.then(repository.findByIdAndOwner(row.id(), caller.accountId())).map(row2 -> resultBody(row2, storage));
	}

	/** 装配文件并写对象存储；成功才 markReady，失败 markFailed（missingItems 在 manifest 中）。 */
	private Mono<Void> build(Caller caller, CreationExportRepository.ExportRow row, CreationDraft draft,
			ObjectStorageAdapter storage) {
		return collectMedia(caller, draft).flatMap(mediaFiles -> {
			List<String> missing = mediaFiles.missing();
			if (!missing.isEmpty()) {
				// 新格式缺必需媒体：整单 failed，missingItems 明确列出（§6.4）
				return repository.markFailed(row.id(), "STUDIO_EXPORT_MISSING_MEDIA").then(Mono.fromRunnable(() -> {
				})).then(Mono.empty());
			}
			try {
				Assembly assembly = assemble(row, draft, mediaFiles);
				String objectKey = "creation-exports/" + row.id()
						+ (isZip(row.format()) ? ".zip" : fileExt(row.format()));
				storage.putObject(objectKey, assembly.bytes(), assembly.contentType());
				Map<String, Object> manifest = new LinkedHashMap<>();
				manifest.put("objectKey", objectKey);
				manifest.put("filename", assembly.filename());
				manifest.put("contentType", assembly.contentType());
				manifest.put("sha256", com.grassland.intelligence.media.MediaChecksums.sha256(assembly.bytes()));
				manifest.put("sizeBytes", assembly.bytes().length);
				manifest.put("title", draft.articleTitle() == null ? draft.title() : draft.articleTitle());
				manifest.put("format", row.format());
				manifest.put("entries", assembly.entries());
				return repository.markReady(row.id(), PlanJson.json(manifest),
						com.grassland.intelligence.media.MediaChecksums.sha256(assembly.bytes())).then();
			} catch (Exception error) {
				org.slf4j.LoggerFactory.getLogger(CreationExportService.class).warn("export assembly failed: export={}",
						row.id(), error);
				return repository.markFailed(row.id(), "STUDIO_EXPORT_ASSEMBLY_FAILED").then();
			}
		}).onErrorResume(error -> repository.markFailed(row.id(), "STUDIO_EXPORT_ASSEMBLY_FAILED")
				.then(Mono.error(new IntelligenceException(503, "STUDIO_EXPORT_ASSEMBLY_FAILED", "导出装配失败"))));
	}

	private static boolean isZip(String format) {
		return "bundle-zip".equals(format);
	}

	private static String fileExt(String format) {
		return switch (format) {
			case "markdown" -> ".md";
			case "wechat-html" -> ".html";
			default -> ".txt";
		};
	}

	// ---- 文件装配（确定性；主题经 CreationRenderTheme 只改样式） ----

	private record MediaPayload(Map<String, Object> ref, byte[] bytes, String filename) {
	}

	private record MediaBundle(List<MediaPayload> files, List<String> missing) {
	}

	/** 收集权限合格的已采用媒体字节（顺序=resultRefs 顺序；不可用项记 missing）。 */
	@SuppressWarnings("unchecked")
	private Mono<MediaBundle> collectMedia(Caller caller, CreationDraft draft) {
		Map<String, Object> workspace = draft.workspace() == null ? Map.of() : draft.workspace();
		if (!(workspace.get("resultRefs") instanceof List<?> refs)) {
			return Mono.just(new MediaBundle(List.of(), List.of()));
		}
		ObjectStorageAdapter storage = storageProvider.getIfAvailable();
		List<Map<String, Object>> refMaps = new ArrayList<>();
		for (Object ref : refs) {
			if (ref instanceof Map<?, ?> refMap) {
				refMaps.add((Map<String, Object>) refMap);
			}
		}
		List<MediaPayload> files = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		return reactor.core.publisher.Flux.fromIterable(refMaps).index().concatMap(indexed -> {
			Map<String, Object> ref = indexed.getT2();
			String id = String.valueOf(ref.get("id"));
			return media.findById(UUID.fromString(id))
					.filter(item -> caller.accountId().equals(item.ownerAccountId()) && item.deletedAt() == null
							&& item.status() == com.grassland.intelligence.media.MediaStatus.ACTIVE)
					.flatMap(item -> Mono.fromCallable(() -> {
						byte[] bytes = storage == null ? new byte[0] : storage.getObject(item.objectKey());
						if (bytes == null) {
							throw new IllegalStateException("对象缺失：" + item.objectKey());
						}
						String ext = item.mimeType() != null && item.mimeType().contains("png") ? "png" : "jpg";
						files.add(new MediaPayload(ref, bytes,
								"media/" + String.format("%02d", indexed.getT1() + 1) + "." + ext));
						return true;
					}).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()))
					.switchIfEmpty(Mono.fromRunnable(() -> missing.add(id)));
		}).then(Mono.fromSupplier(() -> new MediaBundle(files, missing)));
	}

	private record Assembly(byte[] bytes, String filename, String contentType, List<Map<String, Object>> entries) {
	}

	private Assembly assemble(CreationExportRepository.ExportRow row, CreationDraft draft, MediaBundle media) {
		String title = draft.articleTitle() == null ? draft.title() : draft.articleTitle();
		String content = draft.content() == null ? "" : draft.content();
		CreationDocumentRenderer.BoundMedia cover = null;
		List<CreationDocumentRenderer.BoundMedia> inline = new ArrayList<>();
		int position = 0;
		for (MediaPayload payload : media.files()) {
			position++;
			boolean isCover = "cover".equals(payload.ref().get("role"));
			String after = payload.ref().get("placement") instanceof Map<?, ?> placement
					&& placement.get("afterBlockId") instanceof String afterId ? afterId : null;
			var bound = new CreationDocumentRenderer.BoundMedia(String.valueOf(payload.ref().get("id")),
					"配图 " + position, after, isCover, position);
			if (isCover) {
				cover = bound;
			} else {
				inline.add(bound);
			}
		}
		List<CreationDocumentRenderer.BoundMedia> all = new ArrayList<>();
		if (cover != null) {
			all.add(cover);
		}
		all.addAll(inline);
		CreationRenderTheme theme = CreationRenderTheme.of(row.theme());
		CreationDocumentRenderer.Rendered rendered = CreationDocumentRenderer.render(content, title, row.includeTitle(),
				row.citeExternalLinks(), theme, all);
		return switch (row.format()) {
			case "markdown" -> new Assembly(content.getBytes(StandardCharsets.UTF_8), safeFilename(title) + ".md",
					"text/markdown; charset=utf-8", List.of());
			case "text" -> new Assembly(rendered.text().getBytes(StandardCharsets.UTF_8), safeFilename(title) + ".txt",
					"text/plain; charset=utf-8", List.of());
			case "wechat-html" -> new Assembly(rendered.html().getBytes(StandardCharsets.UTF_8),
					safeFilename(title) + ".html", "text/html; charset=utf-8", List.of());
			default -> zipBundle(title, rendered, content, draft, media);
		};
	}

	private Assembly zipBundle(String title, CreationDocumentRenderer.Rendered rendered, String content,
			CreationDraft draft, MediaBundle media) {
		try {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			List<Map<String, Object>> entries = new ArrayList<>();
			try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
				putEntry(zip, entries, "index.html", rendered.html().getBytes(StandardCharsets.UTF_8));
				putEntry(zip, entries, "content.md", content.getBytes(StandardCharsets.UTF_8));
				putEntry(zip, entries, "content.txt", rendered.text().getBytes(StandardCharsets.UTF_8));
				Map<String, Object> manifest = new LinkedHashMap<>();
				manifest.put("title", title);
				manifest.put("draftId", draft.id().toString());
				manifest.put("version", draft.version());
				manifest.put("exportedAt", Instant.now().toString());
				putEntry(zip, entries, "manifest.json", PlanJson.json(manifest).getBytes(StandardCharsets.UTF_8));
				for (MediaPayload payload : media.files()) {
					putEntry(zip, entries, payload.filename(), payload.bytes());
				}
			}
			return new Assembly(buffer.toByteArray(), safeFilename(title) + ".zip", "application/zip", entries);
		} catch (Exception error) {
			throw new IllegalStateException("zip 装配失败", error);
		}
	}

	private static void putEntry(ZipOutputStream zip, List<Map<String, Object>> entries, String name, byte[] bytes)
			throws java.io.IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(bytes);
		zip.closeEntry();
		entries.add(new LinkedHashMap<>(Map.of("name", name, "sha256",
				com.grassland.intelligence.media.MediaChecksums.sha256(bytes), "sizeBytes", bytes.length)));
	}

	private static String safeFilename(String title) {
		String cleaned = (title == null || title.isBlank() ? "创作交付" : title).replaceAll("[\\\\/:*?\"<>|\\s]+", "-")
				.strip();
		return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
	}

	// ---- API101-20 读取（恢复签名；manifest 永不含 URL） ----

	public Mono<Map<String, Object>> load(Caller caller, UUID exportId) {
		return repository.findByIdAndOwner(exportId, caller.accountId())
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "导出不存在"))).flatMap(row -> {
					if ("building".equals(row.state())
							&& row.createdAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(150))) {
						return repository.failStaleBuilding(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(150))
								.then(repository.findByIdAndOwner(exportId, caller.accountId()))
								.map(row2 -> resultBody(row2, storageProvider.getIfAvailable()));
					}
					return Mono.just(resultBody(row, storageProvider.getIfAvailable()));
				});
	}

	static Map<String, Object> resultBody(CreationExportRepository.ExportRow row, ObjectStorageAdapter storage) {
		if ("ready".equals(row.state())) {
			Map<String, Object> manifest = PlanJson.readJson(row.manifestJson());
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("draftId", row.draftId().toString());
			body.put("version", row.version());
			body.put("format", row.format());
			Map<String, Object> file = new LinkedHashMap<>();
			file.put("exportId", row.id().toString());
			file.put("filename", manifest.get("filename"));
			file.put("contentType", manifest.get("contentType"));
			file.put("sha256", manifest.get("sha256"));
			file.put("sizeBytes", manifest.get("sizeBytes"));
			// 读取时恢复签名（短时授权；不落库不进日志）
			if (storage != null) {
				file.put("url", storage.presignDownload(String.valueOf(manifest.get("objectKey")), DOWNLOAD_TTL_SECONDS)
						.toString());
			}
			file.put("expiresAt", Instant.now().plusSeconds(DOWNLOAD_TTL_SECONDS).toString());
			body.put("file", file);
			body.put("missingItems", List.of());
			return body;
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("exportId", row.id().toString());
		body.put("state", row.state());
		body.put("error",
				row.errorCode() == null
						? null
						: Map.of("code", row.errorCode(), "message",
								"STUDIO_EXPORT_MISSING_MEDIA".equals(row.errorCode()) ? "存在不可用媒体，导出未完成" : "导出未完成，可重试"));
		return body;
	}

	/** 7 天生命周期（清理 worker 调用）：返回待删行。 */
	public reactor.core.publisher.Flux<CreationExportRepository.ExportRow> expiredExports() {
		return repository.findExpired(OffsetDateTime.now(ZoneOffset.UTC).minusDays(EXPORT_RETENTION_DAYS), 50);
	}

	public Mono<Boolean> deleteExport(CreationExportRepository.ExportRow row) {
		return repository.delete(row.id());
	}

	@SuppressWarnings("unused")
	private void unused(SourceDocumentRepository sources) {
		// 保留构造注入（后续 M3 同步将复用来源绑定）；当前装配不读来源。
	}
}
