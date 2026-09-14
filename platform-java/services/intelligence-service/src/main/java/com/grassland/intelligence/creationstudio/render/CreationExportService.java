package com.grassland.intelligence.creationstudio.render;

import com.grassland.intelligence.creationassistant.CreationDraft;
import com.grassland.intelligence.creationstudio.CreationStudioProperties;
import com.grassland.intelligence.creationstudio.plan.PlanJson;
import com.grassland.intelligence.media.MediaChecksums;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class CreationExportService {
	public static final Set<String> NEW_FORMATS = Set.of("markdown", "text", "wechat-html", "bundle-zip");
	static final long DOWNLOAD_TTL_SECONDS = 900;
	private static final long MAX_BYTES = 100L * 1024 * 1024;
	private final CreationExportRepository repository;
	private final CreationRenderService renderer;
	private final CreationImageProcessor worker;
	private final CreationStudioProperties properties;
	private final ObjectProvider<ObjectStorageAdapter> storageProvider;

	public CreationExportService(CreationExportRepository repository, CreationRenderService renderer,
			CreationImageProcessor worker, CreationStudioProperties properties,
			ObjectProvider<ObjectStorageAdapter> storageProvider) {
		this.repository = repository;
		this.renderer = renderer;
		this.worker = worker;
		this.properties = properties;
		this.storageProvider = storageProvider;
	}
	public record ExportCommand(UUID requestId, int version, String format, String theme, boolean includeTitle,
			boolean citeExternalLinks) {
	}

	public Mono<Map<String, Object>> create(Caller caller, UUID draftId, ExportCommand command) {
		if (!NEW_FORMATS.contains(command.format()) || !Set.of("standard", "compact").contains(command.theme())
				|| command.version() < 1 || command.requestId() == null) {
			return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "导出格式、版本或主题无效"));
		}
		return renderer.loadSnapshot(caller, draftId, command.version()).flatMap(draft -> repository
				.findByOwnerAndRequestId(caller.accountId(), command.requestId().toString())
				.flatMap(row -> sameCommand(row, draftId, command) ? existing(caller, row) : Mono.error(conflict()))
				.switchIfEmpty(Mono.defer(() -> {
					if (!properties.isWritesEnabled())
						return Mono.error(new IntelligenceException(404, "STUDIO_DISABLED", "图文导出暂未开放"));
					UUID id = UUID.randomUUID();
					String hash = PlanJson.sha256(PlanJson.json(Map.of("draftId", draftId.toString(), "version",
							command.version(), "format", command.format(), "theme", command.theme(), "includeTitle",
							command.includeTitle(), "citeExternalLinks", command.citeExternalLinks())));
					return repository.claimOrGet(id, caller.accountId(), command.requestId().toString(), draftId,
							command.version(), command.format(), command.theme(), command.includeTitle(),
							command.citeExternalLinks(), hash).flatMap(row -> {
								if (!sameCommand(row, draftId, command))
									return Mono.error(conflict());
								return row.id().equals(id) ? build(caller, row) : existing(caller, row);
							});
				})));
	}

	private static boolean sameCommand(CreationExportRepository.ExportRow row, UUID draftId, ExportCommand command) {
		return row.draftId().equals(draftId) && row.version() == command.version()
				&& row.format().equals(command.format()) && row.theme().equals(command.theme())
				&& row.includeTitle() == command.includeTitle()
				&& row.citeExternalLinks() == command.citeExternalLinks();
	}
	private static IntelligenceException conflict() {
		return new IntelligenceException(409, "STUDIO_OPERATION_CONFLICT", "同一请求标识已用于不同的导出");
	}
	private Mono<Map<String, Object>> existing(Caller caller, CreationExportRepository.ExportRow row) {
		if ("ready".equals(row.state()))
			return readable(caller, row);
		if ("failed".equals(row.state()))
			return repository.claimRebuild(row).flatMap(claim -> build(caller, claim))
					.switchIfEmpty(repository.findByIdAndOwner(row.id(), caller.accountId())
							.flatMap(value -> "ready".equals(value.state())
									? readable(caller, value)
									: Mono.just(resultBody(value, null))));
		return Mono.just(resultBody(row, null));
	}

	private ObjectStorageAdapter storage() {
		ObjectStorageAdapter storage = storageProvider.getIfAvailable();
		if (storage == null)
			throw new IntelligenceException(503, "STUDIO_DEPENDENCY_UNAVAILABLE", "对象存储不可用");
		return storage;
	}

	private Mono<Map<String, Object>> build(Caller caller, CreationExportRepository.ExportRow row) {
		return Mono.defer(() -> {
			ObjectStorageAdapter storage = storage();
			return renderer.prepare(caller, row.draftId(), row.version()).flatMap(prepared -> {
				if (!prepared.unavailable().isEmpty())
					return failMissing(row, prepared.unavailable());
				return collectMedia(prepared, storage).flatMap(files -> worker
						.bounded(() -> assemble(row, prepared, files)).flatMap(assembly -> worker.bounded(() -> {
							String key = "creation-exports/" + row.id() + "/" + row.buildToken()
									+ (assembly.contentType().equals("application/zip")
											? ".zip"
											: row.format().equals("markdown")
													? ".md"
													: row.format().equals("text") ? ".txt" : ".html");
							storage.putObject(key, assembly.bytes(), assembly.contentType());
							Map<String, Object> manifest = new LinkedHashMap<>();
							manifest.put("objectKey", key);
							manifest.put("filename", assembly.filename());
							manifest.put("contentType", assembly.contentType());
							manifest.put("sha256", MediaChecksums.sha256(assembly.bytes()));
							manifest.put("sizeBytes", assembly.bytes().length);
							manifest.put("format", row.format());
							manifest.put("entries", assembly.entries());
							manifest.put("title", CreationRenderService.title(prepared.draft()));
							manifest.put(
									"mediaFiles", files
											.stream().map(file -> Map.of("mediaId", file.mediaId(), "path",
													file.filename(), "sha256", MediaChecksums.sha256(file.bytes())))
											.toList());
							return manifest;
						}))
						.flatMap(manifest -> repository.markReady(row.id(), row.buildToken(), PlanJson.json(manifest),
								String.valueOf(manifest.get("sha256"))))
						.then(repository.findByIdAndOwner(row.id(), caller.accountId()))
						.flatMap(saved -> "ready".equals(saved.state())
								? readable(caller, saved)
								: Mono.just(resultBody(saved, null))));
			});
		}).timeout(Duration.ofSeconds(120)).onErrorResume(error -> {
			String code = error instanceof IntelligenceException failure && failure.code() != null
					? failure.code()
					: "STUDIO_DEPENDENCY_UNAVAILABLE";
			return repository.markFailed(row.id(), row.buildToken(), code, List.of()).then(Mono.error(error));
		});
	}

	private Mono<Map<String, Object>> failMissing(CreationExportRepository.ExportRow row, List<String> missing) {
		return repository.markFailed(row.id(), row.buildToken(), "STUDIO_MEDIA_UNAVAILABLE", missing).then(Mono.error(
				new IntelligenceException(409, "STUDIO_MEDIA_UNAVAILABLE", "以下媒体不可用：" + String.join("、", missing))));
	}
	private record MediaPayload(String mediaId, byte[] bytes, String filename) {
	}
	private Mono<List<MediaPayload>> collectMedia(CreationRenderService.PreparedDocument prepared,
			ObjectStorageAdapter storage) {
		long declared = prepared.media().stream().mapToLong(item -> item.media().sizeBytes()).sum();
		if (declared > MAX_BYTES)
			return Mono.error(new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", "媒体源文件合计超过 100 MiB"));
		long[] actual = {0};
		Set<UUID> seen = new java.util.HashSet<>();
		return Flux.fromIterable(prepared.media()).filter(item -> seen.add(item.media().id())).index()
				.concatMap(indexed -> {
					var item = indexed.getT2();
					if (item.media().sizeBytes() > 10L * 1024 * 1024)
						return Mono.error(new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED",
								"媒体 " + item.media().id() + " 超过单图 10 MiB"));
					return worker.bounded(() -> storage.getObject(item.media().objectKey())).switchIfEmpty(Mono.error(
							new IntelligenceException(409, "STUDIO_MEDIA_UNAVAILABLE", "媒体文件不可用：" + item.media().id())))
							.flatMap(bytes -> {
								actual[0] += bytes.length;
								if (actual[0] > MAX_BYTES)
									return Mono.error(new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED",
											"媒体源文件合计超过 100 MiB"));
								if (item.media().checksum() != null && !item.media().checksum().isBlank()
										&& !item.media().checksum().equals(MediaChecksums.sha256(bytes))) {
									return Mono.error(new IntelligenceException(409, "STUDIO_MEDIA_UNAVAILABLE",
											"媒体文件校验失败：" + item.media().id()));
								}
								return worker.validateAndDecode(bytes)
										.map(decoded -> new MediaPayload(item.media().id().toString(), bytes,
												"images/" + String.format("%02d", indexed.getT1() + 1) + "-"
														+ ("cover".equals(item.ref().get("role")) ? "cover" : "content")
														+ ("png".equals(decoded.format()) ? ".png" : ".jpg")));
							});
				}).collectList();
	}
	private record Assembly(byte[] bytes, String filename, String contentType, List<Map<String, Object>> entries) {
	}

	private Assembly assemble(CreationExportRepository.ExportRow row, CreationRenderService.PreparedDocument prepared,
			List<MediaPayload> media) {
		var rendered = CreationRenderService.renderPrepared(prepared, row.theme(), row.includeTitle(),
				row.citeExternalLinks());
		if (!rendered.unresolvedMediaIds().isEmpty()) {
			throw new IntelligenceException(409, "STUDIO_MEDIA_UNAVAILABLE",
					"图片或段落位置尚未核对：" + String.join("、", rendered.unresolvedMediaIds()));
		}
		Map<String, String> paths = new LinkedHashMap<>();
		media.forEach(file -> paths.put(file.mediaId(), file.filename()));
		var html = org.jsoup.Jsoup.parseBodyFragment(rendered.html());
		html.outputSettings().prettyPrint(false);
		for (var image : html.select("img[data-media-id]")) {
			String path = paths.get(image.attr("data-media-id"));
			if (path == null)
				throw new IntelligenceException(409, "STUDIO_MEDIA_UNAVAILABLE", "图片包缺少必需图片");
			image.attr("src", path);
		}
		String title = CreationRenderService.title(prepared.draft());
		String htmlFile = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>"
				+ org.jsoup.nodes.Entities.escape(title) + "</title></head><body>" + html.body().html()
				+ "</body></html>";
		String markdown = "---\ntitle: " + PlanJson.json(title) + "\nplatform: "
				+ PlanJson.json(prepared.draft().platform()) + "\n---\n\n"
				+ CreationDocumentRenderer.rewriteMarkdownMedia(rendered.markdown(), paths);
		if ("text".equals(row.format()))
			return new Assembly(bytes(rendered.text()), safeFilename(title) + ".txt", "text/plain; charset=utf-8",
					List.of());
		if (media.isEmpty() && !"bundle-zip".equals(row.format())) {
			boolean md = "markdown".equals(row.format());
			return new Assembly(bytes(md ? markdown : htmlFile), safeFilename(title) + (md ? ".md" : ".html"),
					md ? "text/markdown; charset=utf-8" : "text/html; charset=utf-8", List.of());
		}
		LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
		boolean bundle = "bundle-zip".equals(row.format());
		if (bundle || "markdown".equals(row.format()))
			files.put("article.md", bytes(markdown));
		if (bundle || "wechat-html".equals(row.format()))
			files.put("article.html", bytes(htmlFile));
		if (bundle) {
			files.put("article.txt", bytes(rendered.text()));
			files.put("publication.json", bytes(PlanJson.json(publication(prepared.draft()))));
			files.put("sources.json",
					bytes(PlanJson.json(prepared.sources().stream()
							.map(source -> Map.of("id", source.id().toString(), "title", source.title(), "kind",
									source.kind(), "rawText", source.rawText(), "normalizedMarkdown",
									source.normalizedMarkdown(), "contentHash", source.contentHash(), "blocks",
									source.blocks(), "sourceRefs", source.sourceRefs()))
							.toList())));
		}
		files.put("README.txt", bytes("请先完整解压再打开文章文件。图片位于 images/，与文章使用相对路径关联。\n" + "此包对应草稿版本 v" + row.version() + "。\n"
				+ String.join("\n", rendered.warnings())));
		media.forEach(file -> files.put(file.filename(), file.bytes()));
		List<Map<String, Object>> entries = new ArrayList<>();
		files.forEach((name, data) -> entries
				.add(Map.of("name", name, "sizeBytes", data.length, "sha256", MediaChecksums.sha256(data))));
		files.put("manifest.json", bytes(PlanJson.json(Map.of("draftId", row.draftId().toString(), "version",
				row.version(), "format", row.format(), "files", entries))));
		try {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
				for (var file : files.entrySet()) {
					ZipEntry entry = new ZipEntry(file.getKey());
					entry.setTime(0);
					zip.putNextEntry(entry);
					zip.write(file.getValue());
					zip.closeEntry();
					if (buffer.size() > MAX_BYTES)
						throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", "图片包超过 100 MiB");
				}
			}
			if (buffer.size() > MAX_BYTES)
				throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", "图片包超过 100 MiB");
			return new Assembly(buffer.toByteArray(), safeFilename(title) + ".zip", "application/zip", entries);
		} catch (java.io.IOException error) {
			throw new IntelligenceException(503, "STUDIO_DEPENDENCY_UNAVAILABLE", "文件打包失败");
		}
	}
	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}
	private static String safeFilename(String title) {
		String name = (title == null ? "创作交付" : title).replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]+", "-").strip();
		if (name.isBlank() || name.equals(".") || name.equals(".."))
			name = "创作交付";
		return name.codePointCount(0, name.length()) > 60 ? name.substring(0, name.offsetByCodePoints(0, 60)) : name;
	}
	private static Map<String, Object> publication(CreationDraft draft) {
		Map<?, ?> delivery = draft.workspace() != null && draft.workspace().get("delivery") instanceof Map<?, ?> map
				? map
				: Map.of();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("title", CreationRenderService.title(draft));
		result.put("platform", draft.platform());
		result.put("contentForm", draft.contentForm());
		for (String key : List.of("summary", "topics", "declarations", "shareCopy", "titleOrOpening",
				"bodyOrDescription")) {
			if (delivery.containsKey(key))
				result.put(key, delivery.get(key));
		}
		return result;
	}

	public Mono<Map<String, Object>> load(Caller caller, UUID exportId) {
		return repository.findByIdAndOwner(exportId, caller.accountId())
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "导出不存在")))
				.flatMap(row -> renderer.loadSnapshot(caller, row.draftId(), row.version()).then(Mono.defer(() -> {
					if ("ready".equals(row.state()))
						return readable(caller, row);
					if ("STUDIO_EXPORT_EXPIRED".equals(row.errorCode()))
						return existing(caller, row);
					if ("building".equals(row.state())
							&& row.buildStartedAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(150))) {
						return repository.failStaleBuilding(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(150))
								.then(repository.findByIdAndOwner(exportId, caller.accountId()))
								.map(value -> resultBody(value, null));
					}
					return Mono.just(resultBody(row, null));
				})));
	}
	private Mono<Map<String, Object>> readable(Caller caller, CreationExportRepository.ExportRow row) {
		return renderer.prepare(caller, row.draftId(), row.version()).flatMap(prepared -> {
			if (!prepared.unavailable().isEmpty())
				return Mono.error(new IntelligenceException(409, "STUDIO_MEDIA_UNAVAILABLE", "媒体授权已失效，无法签发下载链接"));
			if (row.readyAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC).minusDays(7))) {
				return repository.expire(row).then(repository.findByIdAndOwner(row.id(), caller.accountId()))
						.flatMap(value -> existing(caller, value));
			}
			ObjectStorageAdapter adapter = storage();
			String key = String.valueOf(PlanJson.readJson(row.manifestJson()).get("objectKey"));
			return worker.bounded(() -> adapter.headObject(key)).flatMap(head -> {
				if (head.isEmpty())
					return repository.expire(row).then(repository.findByIdAndOwner(row.id(), caller.accountId()))
							.flatMap(value -> existing(caller, value));
				return Mono.just(resultBody(row, adapter));
			});
		});
	}
	static Map<String, Object> resultBody(CreationExportRepository.ExportRow row, ObjectStorageAdapter storage) {
		Map<String, Object> manifest = PlanJson.readJson(row.manifestJson());
		if ("ready".equals(row.state())) {
			if (storage == null)
				throw new IntelligenceException(503, "STUDIO_DEPENDENCY_UNAVAILABLE", "对象存储不可用");
			Map<String, Object> file = new LinkedHashMap<>();
			file.put("exportId", row.id().toString());
			for (String key : List.of("filename", "contentType", "sha256", "sizeBytes"))
				file.put(key, manifest.get(key));
			String disposition = "attachment; filename*=UTF-8''" + java.net.URLEncoder
					.encode(String.valueOf(manifest.get("filename")), StandardCharsets.UTF_8).replace("+", "%20");
			file.put("url", storage
					.presignDownload(String.valueOf(manifest.get("objectKey")), DOWNLOAD_TTL_SECONDS, disposition)
					.toString());
			file.put("expiresAt", Instant.now().plusSeconds(DOWNLOAD_TTL_SECONDS).toString());
			return Map.of("draftId", row.draftId().toString(), "version", row.version(), "format", row.format(), "file",
					file, "missingItems", List.of());
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("exportId", row.id().toString());
		body.put("state", row.state());
		body.put("error",
				row.errorCode() == null
						? null
						: Map.of("code", row.errorCode(), "message",
								"导出未完成，请核对媒体与保存状态后重试"
										+ (manifest.get("missingItems") instanceof List<?> missing && !missing.isEmpty()
												? "：" + missing
												: "")));
		return body;
	}
	public Flux<CreationExportRepository.ExportRow> expiredExports() {
		return repository.findExpired(OffsetDateTime.now(ZoneOffset.UTC).minusDays(7), 50);
	}
	public Mono<Boolean> deleteExport(CreationExportRepository.ExportRow row) {
		return repository.expire(row);
	}
}
