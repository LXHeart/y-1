package com.grassland.intelligence.hypit.asset;

import com.grassland.intelligence.hypit.asset.HypitArchiveDownloader.SidecarBytes;
import com.grassland.intelligence.hypit.build.HypitBuildRepository;
import com.grassland.intelligence.hypit.build.HypitBuildRepository.BuildRow;
import com.grassland.intelligence.hypit.build.HypitOutputRepository;
import com.grassland.intelligence.hypit.build.HypitOutputRepository.OutputRow;
import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.media.HypitMediaArchiveAdapter;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 自动归档（任务书 #107-2 C107-10 步骤 6/7）：结果发现可用 Output 后幂等归档， 不等用户打开页面。流程 =
 * claim（pending→archiving CAS）→ sidecar results.export → 内部资源端点下载真实字节 →
 * 既有媒体管线（幂等 media/outbox）→ completeArchive。 部分失败/取消仍归档用户需要的 Output（逐 Output
 * 独立推进）。
 */
@Service
public class HypitArchiveService {

	private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(60);
	private static final long MAX_BYTES = 200L * 1024 * 1024;

	private final HypitOutputRepository outputs;
	private final HypitBuildRepository builds;
	private final HypitSidecarClient sidecar;
	private final HypitProperties properties;
	private final HypitMediaArchiveAdapter media;
	private final org.springframework.r2dbc.core.DatabaseClient db;

	public HypitArchiveService(HypitOutputRepository outputs, HypitBuildRepository builds, HypitSidecarClient sidecar,
			HypitProperties properties, HypitMediaArchiveAdapter media,
			org.springframework.r2dbc.core.DatabaseClient db) {
		this.outputs = outputs;
		this.builds = builds;
		this.sidecar = sidecar;
		this.properties = properties;
		this.media = media;
		this.db = db;
	}

	/** 单个 Output 归档：已认领/已归档时查询现状返回，不启动第二个上传。 */
	public Mono<OutputRow> archiveOutput(UUID outputId) {
		return outputs.claimArchive(outputId).flatMap(
				claimed -> claimed ? doArchive(outputId) : outputs.findById(outputId).map(this::currentOrDone));
	}

	/** 按名归档（名字缺省=全部可用 Output）；单 Output 失败不拖垮整批（步骤 6）。 */
	public reactor.core.publisher.Flux<OutputRow> archiveByNames(UUID buildId, java.util.List<String> names) {
		java.util.function.Predicate<OutputRow> selected = names == null || names.isEmpty()
				? row -> true
				: row -> names.contains(row.outputName());
		return outputs.findByBuild(buildId).filter(selected).flatMap(row -> archiveOutput(row.id())
				.onErrorResume(error -> outputs.findById(row.id()).map(this::currentOrDone)));
	}

	/** 其他进程已 claim：已 archived 直接回现状；仍在 archiving 视为并发中，不重复上传。 */
	private OutputRow currentOrDone(OutputRow row) {
		if ("failed".equals(row.archiveState())) {
			throw new IntelligenceException(HttpStatus.CONFLICT.value(), "hypit_archive_failed",
					"先前归档失败：" + row.errorCode());
		}
		return row;
	}

	private Mono<OutputRow> doArchive(UUID outputId) {
		return outputs.findById(outputId)
				.switchIfEmpty(Mono.error(new IllegalStateException("output row vanished: " + outputId))).flatMap(
						output -> builds.findById(output.buildId())
								.switchIfEmpty(Mono.error(new IllegalStateException("build row vanished")))
								.flatMap(build -> ownerOf(build.projectId())
										.flatMap(owner -> exportBytes(output, build)
												.flatMap(bytes -> media
														.storeArchiveBytes(output.id(), owner, null, bytes.value(),
																bytes.mediaType())
														.ignoreElement().then(advanceArchive(output, bytes)))
												.onErrorResume(error -> outputs
														.failArchive(output.id(), "hypit_archive_failed")
														.then(Mono.<OutputRow>error(new IntelligenceException(
																HttpStatus.BAD_GATEWAY.value(), "hypit_archive_failed",
																"归档失败：" + String.valueOf(error.getMessage()))))))));
	}

	private Mono<OutputRow> advanceArchive(OutputRow output, SidecarBytes bytes) {
		UUID deterministic = HypitMediaArchiveAdapter.deterministicMediaId(output.id());
		return outputs.completeArchive(output.id(), deterministic, bytes.handle()).then(outputs.findById(output.id()));
	}

	/** 归档是系统发起（无用户上下文），owner 直查工程行。 */
	private Mono<String> ownerOf(UUID projectId) {
		return db.sql("SELECT account_id FROM hypit_project WHERE id = CAST(:id AS uuid)")
				.bind("id", projectId.toString()).map((row, meta) -> row.get("account_id", String.class)).one()
				.switchIfEmpty(Mono.error(new IllegalStateException("project row vanished")));
	}

	private Mono<SidecarBytes> exportBytes(OutputRow output, BuildRow build) {
		if (!properties.enabled() || !sidecar.configured()) {
			return Mono.error(new IntelligenceException(HttpStatus.SERVICE_UNAVAILABLE.value(),
					"hypit_backend_unavailable", "引擎未启用，无法归档输出"));
		}
		Map<String, Object> payload = new HashMap<>();
		payload.put("projectId", build.projectId().toString());
		payload.put("engineBuildId", build.engineBuildId());
		payload.put("output", output.outputName());
		return sidecar.commandAsync("results-archive-" + output.id(), "results.export", payload).timeout(EXPORT_TIMEOUT)
				.flatMap(command -> {
					if (command.result() == null) {
						return Mono.error(new IntelligenceException(HttpStatus.BAD_GATEWAY.value(),
								"hypit_engine_error", "results.export 失败"));
					}
					@SuppressWarnings("unchecked")
					Map<String, Object> exported = (Map<String, Object>) command.result();
					String kind = String.valueOf(exported.get("kind"));
					String handle = exported.get("handle") == null ? null : String.valueOf(exported.get("handle"));
					String mediaType = exported.get("mediaType") == null
							? "application/octet-stream"
							: String.valueOf(exported.get("mediaType"));
					if ("resource".equals(kind)) {
						if (handle == null) {
							return Mono.error(
									new IntelligenceException(HttpStatus.CONFLICT.value(), "hypit_external_unavailable",
											"Output " + output.outputName() + " 依赖外部资源且不可达，不导出空包"));
						}
						return HypitArchiveDownloader.download(properties.sidecarBaseUrl(), properties.internalToken(),
								handle, MAX_BYTES);
					}
					if ("composite".equals(kind) || "scalar".equals(kind)) {
						String document = String.valueOf(exported.get("valueDocument") == null
								? exported.get("value")
								: exported.get("valueDocument"));
						return Mono.just(new SidecarBytes(document.getBytes(java.nio.charset.StandardCharsets.UTF_8),
								"application/json", handle));
					}
					return Mono.error(new IntelligenceException(HttpStatus.CONFLICT.value(),
							"hypit_external_unavailable", "未知导出类型 " + kind));
				});
	}
}
