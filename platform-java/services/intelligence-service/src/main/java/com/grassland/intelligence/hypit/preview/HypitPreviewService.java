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

	private static final Duration SESSION_TIMEOUT = Duration.ofSeconds(30);
	/**
	 * §6.10：预览会话 TTL 1800 秒（与 broker sessions.ts PREVIEW_SESSION_TTL_SECONDS 对齐）。
	 */
	static final long PREVIEW_TTL_SECONDS = 1800;

	private final HypitProperties properties;
	private final HypitSidecarClient sidecar;
	private final HypitProjectRepository projects;
	private final HypitBuildRepository builds;
	private final HypitBuildService buildService;
	private final com.grassland.intelligence.hypit.studio.HypitSessionRepository sessions;
	private final DatabaseClient db;

	public HypitPreviewService(HypitProperties properties, HypitSidecarClient sidecar, HypitProjectRepository projects,
			HypitBuildRepository builds, HypitBuildService buildService,
			com.grassland.intelligence.hypit.studio.HypitSessionRepository sessions, DatabaseClient db) {
		this.properties = properties;
		this.sidecar = sidecar;
		this.projects = projects;
		this.builds = builds;
		this.buildService = buildService;
		this.sessions = sessions;
		this.db = db;
	}

	public record PreviewSessionView(String sessionId, String ticketUrl, Instant expiresAt, Long revision,
			List<String> missingMaterials) {
	}

	/**
	 * C107F2-22（F18 / §6.9-6.10）：预览会话=先登记 PG hypit_session（kind=preview， broker 绑定该
	 * id）→ sidecar preview.session（含 ownerAccountId）→ markActive → 返回非空
	 * /preview/<sid>/ URL。空 URL/空会话/缺版本=引擎错误，绝不发空 src 成功 信封；缺失素材如实上报且不生成假画面。不隐式
	 * build（Build 数不变断言保留）。
	 */
	public Mono<PreviewSessionView> openSession(String accountId, UUID projectId, UUID requestId, String runFile,
			Long revision) {
		return projects.findOwned(accountId, projectId).switchIfEmpty(Mono.error(HypitAccessService.notFound()))
				.filter(project -> !"deleted".equals(project.status()))
				.switchIfEmpty(Mono.error(HypitAccessService.notFound())).flatMap(project -> {
					if (!engineAvailable()) {
						return Mono.error(HypitAccessService.unavailable("预览会话"));
					}
					long boundRevision = revision == null ? project.revision() : revision;
					if (boundRevision <= 0) {
						return Mono.error(new IntelligenceException(HttpStatus.CONFLICT.value(), "hypit_state_conflict",
								"工程尚无可用修订，先完成初始化。"));
					}
					String effectiveRun = runFile == null || runFile.isBlank() ? "main.svrun" : runFile;
					String sessionId = "pv-" + UUID.randomUUID().toString().replace("-", "");
					Instant expiresAt = Instant.now().plusSeconds(PREVIEW_TTL_SECONDS);
					return sessions
							.insertStarting(sessionId, projectId, accountId, "preview", effectiveRun, boundRevision,
									true, expiresAt)
							.flatMap(row -> dispatchAndActivate(accountId, row, requestId)
									.onErrorResume(error -> sessions.markFailed(sessionId)
											.then(Mono.error(error instanceof IntelligenceException intelligenceError
													? intelligenceError
													: new IntelligenceException(HttpStatus.BAD_GATEWAY.value(),
															"hypit_engine_error", "preview.session 失败")))));
				});
	}

	/** sidecar 派发 → markActive → 非空 URL 才算成功（C22 步骤 4 红线）。 */
	private Mono<PreviewSessionView> dispatchAndActivate(String accountId,
			com.grassland.intelligence.hypit.studio.HypitSessionRepository.SessionRow row, UUID requestId) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("sessionId", row.id());
		payload.put("projectId", row.projectId().toString());
		payload.put("ownerAccountId", accountId);
		payload.put("runFile", row.runFile());
		payload.put("revision", row.revision());
		String commandId = "preview-session-" + (requestId == null ? UUID.randomUUID() : requestId);
		return sidecar.commandAsync(commandId, "preview.session", payload).timeout(SESSION_TIMEOUT).flatMap(command -> {
			if (!(command.result() instanceof Map<?, ?> rawResult)) {
				throw new IntelligenceException(HttpStatus.BAD_GATEWAY.value(), "hypit_engine_error",
						"preview.session 失败");
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> result = (Map<String, Object>) rawResult;
			String previewUrl = String.valueOf(result.getOrDefault("previewUrl", ""));
			String brokerSessionId = String.valueOf(result.getOrDefault("sessionId", ""));
			// 步骤 4：URL/会话缺失视为引擎错误（不回退当前 head 或空 src）。
			if (previewUrl.isBlank() || brokerSessionId.isBlank() || !brokerSessionId.equals(row.id())) {
				throw new IntelligenceException(HttpStatus.BAD_GATEWAY.value(), "hypit_engine_error",
						"预览会话返回不完整（缺 URL/会话）");
			}
			return sessions.markActive(row.id()).map(active -> new PreviewSessionView(active.id(), previewUrl,
					active.expiresAt(), active.revision(), missingMaterialsOf(result)));
		});
	}

	/**
	 * C107F2-20（§6.9）：关闭预览会话——幂等 200 closed:true。预览是瞬态展示 （broker 侧会话随 TTL
	 * 自然回收），此处尽力通知 sidecar，失败不阻断。
	 */
	public Mono<PreviewSessionView> closeSession(String accountId, UUID projectId, String sessionId) {
		return projects.findOwned(accountId, projectId).switchIfEmpty(Mono.error(HypitAccessService.notFound()))
				.then(sessions.findById(sessionId)
						.filter(row -> row.projectId().equals(projectId) && row.accountId().equals(accountId)
								&& "preview".equals(row.kind()))
						.flatMap(row -> sessions.markClosed(sessionId).then(Mono.just(row)))
						.onErrorResume(error -> Mono.empty()))
				.then(sidecar.commandAsync("preview-close-" + UUID.randomUUID(), "preview.session.close",
						Map.of("sessionId", sessionId)).onErrorResume(error -> Mono.empty()))
				.then(Mono.just(new PreviewSessionView(sessionId, "", Instant.EPOCH, null, List.of())));
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
