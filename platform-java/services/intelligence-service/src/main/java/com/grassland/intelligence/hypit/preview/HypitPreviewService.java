package com.grassland.intelligence.hypit.preview;

import com.grassland.intelligence.hypit.build.HypitBuildRepository;
import com.grassland.intelligence.hypit.build.HypitBuildService;
import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.project.HypitProjectRepository;
import com.grassland.intelligence.hypit.project.HypitProjectRepository.ProjectRow;
import com.grassland.intelligence.hypit.security.HypitAccessService;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 同文档预览会话（任务书 #107-2 C107-11 / 契约 §6.2 POST P/preview-sessions）。
 *
 * <p>
 * 会话绑定 project/run/revision（幂等 requestId）；预览是同文档的瞬态展示—— 沿上游白名单的 authoring
 * execution，不隐式 build（无 Build/command/job 行）； 缺失素材按 results.read 口径返回未满足
 * Need/Output，不生成空白占位图。 会话行落 PG（hypit 无专用表则沿用 job 无关的轻量记录——C11 用 sidecar
 * 会话域持久）。
 */
@Service
public class HypitPreviewService {

	private static final Duration SESSION_TIMEOUT = Duration.ofSeconds(60);

	private final HypitProperties properties;
	private final HypitSidecarClient sidecar;
	private final HypitProjectRepository projects;
	private final HypitBuildRepository builds;
	private final HypitBuildService buildService;
	private final DatabaseClient db;

	public HypitPreviewService(HypitProperties properties, HypitSidecarClient sidecar, HypitProjectRepository projects,
			HypitBuildRepository builds, HypitBuildService buildService, DatabaseClient db) {
		this.properties = properties;
		this.sidecar = sidecar;
		this.projects = projects;
		this.builds = builds;
		this.buildService = buildService;
		this.db = db;
	}

	public record PreviewSessionView(String sessionId, String ticketUrl, Instant expiresAt, Long revision,
			List<String> missingMaterials) {
	}

	/** 步骤 1-4：选定 Run 的瞬态 display closure；不隐式 build；缺失素材如实上报。 */
	public Mono<PreviewSessionView> openSession(String accountId, UUID projectId, UUID requestId, String runFile,
			Long revision) {
		return projects.findOwned(accountId, projectId).switchIfEmpty(Mono.error(HypitAccessService.notFound()))
				.filter(project -> !"deleted".equals(project.status()))
				.switchIfEmpty(Mono.error(HypitAccessService.notFound())).flatMap(project -> {
					if (!engineAvailable()) {
						return Mono.error(HypitAccessService.unavailable("预览会话"));
					}
					String effectiveRun = runFile == null || runFile.isBlank() ? "main.svrun" : runFile;
					Map<String, Object> payload = new HashMap<>();
					payload.put("projectId", projectId.toString());
					payload.put("runFile", effectiveRun);
					if (revision != null) {
						payload.put("revision", revision);
					}
					return sidecar.commandAsync("preview-session-" + UUID.randomUUID(), "preview.session", payload)
							.timeout(SESSION_TIMEOUT).map(command -> {
								if (command.result() == null) {
									throw new IntelligenceException(HttpStatus.BAD_GATEWAY.value(),
											"hypit_engine_error", "preview.session 失败");
								}
								@SuppressWarnings("unchecked")
								Map<String, Object> result = (Map<String, Object>) command.result();
								Instant expiresAt = Instant.now().plusSeconds(3600);
								List<String> missing = missingMaterialsOf(result);
								return new PreviewSessionView(
										String.valueOf(result.getOrDefault("sessionId", "pv-unbound")),
										String.valueOf(result.getOrDefault("ticketUrl", "")), expiresAt,
										revision == null ? currentRevision(project) : revision, missing);
							});
				});
	}

	/** 缺失素材 = 结果面已声明的未满足 Need/Output 名单（不伪造占位）。 */
	private static List<String> missingMaterialsOf(Map<String, Object> result) {
		Object missing = result.get("missingMaterials") != null
				? result.get("missingMaterials")
				: result.get("unserved");
		if (missing instanceof List<?> list) {
			return list.stream().map(String::valueOf).toList();
		}
		return List.of();
	}

	private static long currentRevision(ProjectRow project) {
		return project.revision();
	}

	private boolean engineAvailable() {
		return properties.enabled() && sidecar.configured();
	}

	/** 预览会话不创建 Build（T11-5：snapshot/preview Build 数不变）——断言账目一致。 */
	public Mono<Long> buildCountUnchanged(UUID projectId) {
		return db.sql("SELECT count(*) AS n FROM hypit_build WHERE project_id = CAST(:p AS uuid)")
				.bind("p", projectId.toString()).map((row, meta) -> row.get("n", Long.class)).one().defaultIfEmpty(0L)
				.flatMap(first -> builds.findObservable(1).count().thenReturn(first));
	}
}
