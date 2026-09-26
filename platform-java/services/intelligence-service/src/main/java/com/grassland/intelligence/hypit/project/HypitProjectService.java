package com.grassland.intelligence.hypit.project;

import com.grassland.intelligence.hypit.api.HypitDtos.Project;
import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import com.grassland.intelligence.hypit.client.HypitSidecarClient.SidecarCommand;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.job.HypitCommandRepository;
import com.grassland.intelligence.hypit.job.HypitCommandRepository.Accepted;
import com.grassland.intelligence.hypit.job.HypitCommandRepository.CommandRow;
import com.grassland.intelligence.hypit.job.HypitJobEventRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository.JobRow;
import com.grassland.intelligence.hypit.security.HypitAccessService;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * Hypit 工程服务（任务书 #107-1 C107-04 / §5.3 provision 补偿流程）。
 *
 * <p>
 * 创建流程（K06.1）：一个短事务落 command + project(provisioning) + job；同 requestId 重放且 hash
 * 不同在事务内即 409，无副作用。事务外调 sidecar {@code workspace.provision}（幂等，同 commandId
 * 重放返回同 workspace）；成功回执后第二个短事务推进 ready + revision + job 终态 + command 结果回写。失败标记
 * provisioning_failed，同 requestId 重试收敛到同一工程。PG 不假装 已 ready，也不用 DB 回滚冒充文件回滚。
 */
@Service
public class HypitProjectService {

	private final HypitProjectRepository projects;
	private final HypitRevisionRepository revisions;
	private final HypitCommandRepository commands;
	private final HypitJobRepository jobs;
	private final HypitJobEventRepository events;
	private final HypitSidecarClient sidecar;
	private final HypitProperties properties;
	private final TransactionalOperator transactions;
	private final org.springframework.r2dbc.core.DatabaseClient db;

	public HypitProjectService(HypitProjectRepository projects, HypitRevisionRepository revisions,
			HypitCommandRepository commands, HypitJobRepository jobs, HypitJobEventRepository events,
			HypitSidecarClient sidecar, HypitProperties properties, TransactionalOperator transactions,
			org.springframework.r2dbc.core.DatabaseClient db) {
		this.projects = projects;
		this.revisions = revisions;
		this.commands = commands;
		this.jobs = jobs;
		this.events = events;
		this.sidecar = sidecar;
		this.properties = properties;
		this.transactions = transactions;
		this.db = db;
	}

	public record CreateResult(Project project, UUID jobId, String jobState) {
	}

	private static IntelligenceException invalid(String message) {
		return new IntelligenceException(400, "hypit_invalid_input", message);
	}

	private static IntelligenceException notFound() {
		return new IntelligenceException(404, "hypit_not_found", "资源不存在。");
	}

	/** canonical JSON 摘要（实质参数；不含 requestId/展示时钟，K06.1.2）。 */
	static String sha256Hex(String canonical) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException("SHA-256 unavailable", error);
		}
	}

	private static String canonicalCreate(String title, String mode, String templateId, String sourceContext) {
		return "{\"title\":" + HypitJson.write(title) + ",\"mode\":\"" + mode + "\""
				+ (templateId == null ? "" : ",\"templateId\":\"" + templateId + "\"")
				+ (sourceContext == null ? "" : ",\"sourceContext\":" + HypitJson.write(sourceContext)) + "}";
	}

	// ------------------------------------------------------------------
	// 创建（§5.3 provision）
	// ------------------------------------------------------------------

	public Mono<CreateResult> create(String accountId, UUID requestId, String title, String mode, String templateId,
			String sourceContext) {
		if (requestId == null) {
			return Mono.error(invalid("requestId 必填。"));
		}
		String normalizedTitle = title == null ? "" : title.trim();
		if (normalizedTitle.isEmpty() || normalizedTitle.length() > 60) {
			return Mono.error(invalid("标题须为 1–60 字。"));
		}
		if (mode == null || !List.of("clone", "brief", "template", "import").contains(mode)) {
			return Mono.error(invalid("mode 必须是 clone/brief/template/import。"));
		}
		// C107-22（TC107-22-02）：sourceContext 归属服务端核验——URL/query 不是权限来源，
		// 他人或未固化（临时）媒体一律拒绝；label 属展示字段不参与判定。
		final String canonical = canonicalCreate(normalizedTitle, mode, templateId, sourceContext);
		final String payloadHash = sha256Hex(canonical);

		// 单事务：command 幂等落库 + 冲突判定 + project/job 分发记录。
		Mono<ProvisioningSeed> acceptedTx = commands
				.insert(accountId, "project.create", requestId, "project", payloadHash,
						"{\"canonical\":" + HypitJson.write(canonical) + "}", null)
				.flatMap(accepted -> accepted.existing() && !accepted.row().payloadHash().equals(payloadHash)
						? Mono.<Accepted>error(HypitCommandRepository.conflict(accepted.row()))
						: Mono.just(accepted))
				.flatMap(accepted -> {
					if (accepted.existing()) {
						return Mono.just(new ProvisioningSeed(accepted.row(), null, null, true));
					}
					UUID projectId = UUID.randomUUID();
					return projects
							.insert(new HypitProjectRepository.ProjectRow(projectId, accountId, UUID.randomUUID(),
									normalizedTitle, mode, "provisioning", 0, 1, null, null, null, null, null))
							// C107-22：source_context 随建库事务落库（canonical JSON 文本）。
							.then(sourceContext == null
									? Mono.empty()
									: db.sql("UPDATE hypit_project SET source_context = CAST(:ctx AS jsonb),"
											+ " updated_at = now() WHERE id = CAST(:id AS uuid)")
											.bind("ctx", sourceContext).bind("id", projectId.toString()).fetch()
											.rowsUpdated().then())
							.then(jobs.insert(new JobRow(UUID.randomUUID(), accepted.row().id(), projectId, accountId,
									"project.provision", "queued", "pending", null, null, null, null, 0, 1, null, null,
									1, null, null, null, null, null, null)))
							.map(job -> new ProvisioningSeed(accepted.row(), projectId, job.id(), false));
				}).as(transactions::transactional);

		return validateSourceContext(accountId, sourceContext)
				.then(acceptedTx.flatMap(seed -> seed.replay() ? replayCreate(seed.command()) : provisionFlow(seed)));
	}

	/**
	 * C107-22 sourceContext 归属核验（TC107-22-02）：kind=media 要求本人且已固化（active——
	 * pending/finalizing 属临时媒体，须先经素材库固化）；kind=analysis（ai_run）要求本人； brief
	 * 不携带引用直接放行。他人资源与不存在的资源同答 404，不泄漏存在性。
	 */
	private Mono<Void> validateSourceContext(String accountId, String sourceContext) {
		if (sourceContext == null || sourceContext.isBlank()) {
			return Mono.empty();
		}
		final Map<String, Object> parsed;
		try {
			parsed = HypitJson.read(sourceContext);
		} catch (RuntimeException error) {
			return Mono.error(invalid("sourceContext 不是合法 JSON。"));
		}
		String kind = HypitJson.stringValue(parsed.get("kind"), "");
		String id = HypitJson.stringValue(parsed.get("id"), "");
		if (!List.of("media", "analysis", "brief").contains(kind)) {
			return Mono.error(invalid("sourceContext.kind 必须是 media/analysis/brief。"));
		}
		if ("brief".equals(kind) || id.isEmpty()) {
			return Mono.empty();
		}
		final UUID refId;
		try {
			refId = UUID.fromString(id);
		} catch (IllegalArgumentException error) {
			return Mono.error(invalid("sourceContext.id 不是合法 uuid。"));
		}
		if ("media".equals(kind)) {
			return db.sql("SELECT owner_account_id, status FROM media_reference WHERE id = CAST(:id AS uuid)")
					.bind("id", refId.toString())
					.map((row,
							meta) -> new String[]{row.get("owner_account_id", String.class),
									row.get("status", String.class)})
					.one().switchIfEmpty(Mono.<String[]>error(notFound()))
					.flatMap(ref -> !accountId.equals(ref[0])
							? Mono.<Void>error(notFound())
							: "active".equals(ref[1])
									? Mono.<Void>empty()
									: Mono.error(new IntelligenceException(HttpStatus.CONFLICT.value(),
											"hypit_source_not_permanent", "克隆参考媒体尚未固化，请先存入素材库后再引用。")));
		}
		return db.sql("SELECT account_id FROM ai_run WHERE id = CAST(:id AS uuid)").bind("id", refId.toString())
				.map((row, meta) -> row.get("account_id", String.class)).one().switchIfEmpty(Mono.error(notFound()))
				.flatMap(owner -> accountId.equals(owner) ? Mono.<Void>empty() : Mono.<Void>error(notFound()));
	}

	/** 事务产物：command 行 + 新建 project/job id（replay=true 表示命中已存在命令）。 */
	private record ProvisioningSeed(CommandRow command, UUID projectId, UUID jobId, boolean replay) {
	}

	private Mono<CreateResult> provisionFlow(ProvisioningSeed seed) {
		return jobs.findById(seed.jobId()).switchIfEmpty(Mono.error(new IllegalStateException("provision job missing")))
				.flatMap(job -> provisionOnSidecar(job.projectId(), "template".equals(projectMode(seed.command())))
						.flatMap(receipt -> convergeProvisioned(seed.command(), job, receipt))
						.onErrorResume(error -> convergeFailed(seed.command(), job, error)));
	}

	private Mono<CreateResult> replayCreate(CommandRow existing) {
		if (existing.resultJson() == null) {
			// 先前请求的事务已落、sidecar 流程未回执：如实等待，不重复副作用。
			return Mono.error(new IntelligenceException(HttpStatus.SERVICE_UNAVAILABLE.value(),
					"hypit_backend_unavailable", "先前创建仍在收敛中，请稍后按同 requestId 重试。"));
		}
		Map<String, Object> result = HypitJson.read(existing.resultJson());
		UUID projectId = UUID.fromString(HypitJson.stringValue(result.get("projectId"), ""));
		UUID jobId = UUID.fromString(HypitJson.stringValue(result.get("jobId"), ""));
		return projects.findOwned(existing.accountId(), projectId).flatMap(project -> toResult(project, jobId))
				.switchIfEmpty(Mono.error(notFound()));
	}

	private Mono<CreateResult> toResult(HypitProjectRepository.ProjectRow project, UUID jobId) {
		return jobs.findById(jobId).map(job -> new CreateResult(toDto(project), jobId, job.state()))
				.defaultIfEmpty(new CreateResult(toDto(project), jobId, "succeeded"));
	}

	private String projectMode(CommandRow command) {
		Map<String, Object> payload = HypitJson.read(command.payloadJson());
		Map<String, Object> canonical = HypitJson.mapValue(null);
		// payloadJson 结构 {"canonical":"<json string>"}；mode 在 canonical 里。
		String canonicalText = HypitJson.stringValue(payload.get("canonical"), "{}");
		Map<String, Object> parsed = HypitJson.read(canonicalText);
		return HypitJson.stringValue(parsed.get("mode"), "clone");
	}

	/** sidecar provision（事务外网络调用；sidecar commandId 与 Java command 稳定关联）。 */
	private Mono<SidecarCommand> provisionOnSidecar(UUID projectId, boolean template) {
		if (!properties.enabled()) {
			return Mono.error(new IllegalStateException("hypit engine disabled"));
		}
		Map<String, Object> payload = new HashMap<>();
		payload.put("projectId", projectId.toString());
		if (template) {
			payload.put("template", true);
		}
		return sidecar.commandAsync("java-provision-" + projectId, "workspace.provision", payload);
	}

	private Mono<CreateResult> convergeProvisioned(CommandRow command, JobRow job, SidecarCommand receipt) {
		Map<String, Object> result = HypitJson.mapValue(receipt.result());
		Map<String, Object> head = HypitJson.mapValue(result.get("head"));
		long revision = HypitJson.longValue(head.get("revision"), 0);
		String manifestHash = HypitJson.stringValue(head.get("manifestHash"), null);
		String projectRoot = HypitJson.stringValue(result.get("projectRoot"), null);
		UUID projectId = job.projectId();
		return Mono
				.defer(() -> projects.markReady(projectId, revision, manifestHash)
						.then(revisions.insert(new HypitRevisionRepository.RevisionRow(UUID.randomUUID(), projectId,
								revision, null, manifestHash == null ? "" : manifestHash,
								projectRoot == null ? "workspace:" + projectId : projectRoot, command.id(),
								command.accountId(), null)))
						.then(jobs.updateState(job.id(), "succeeded", null, null))
						.then(events.append(job.id(), "terminal",
								HypitJson.write(Map.of("state", "succeeded", "revision", revision))))
						.then(commands.saveResult(command.id(), "succeeded",
								HypitJson.write(
										Map.of("projectId", projectId.toString(), "jobId", job.id().toString()))))
						.then(projects.findOwned(command.accountId(), projectId))
						.map(project -> new CreateResult(toDto(project), job.id(), "succeeded")))
				.switchIfEmpty(Mono.error(new IllegalStateException("project vanished after provision")))
				.as(transactions::transactional);
	}

	/**
	 * provision 失败收敛（§5.3 统一 202）：失败态如实落在 project(provisioning_failed) 与
	 * job(failed)+终态事件；command 落 result 供同 requestId 重放读取（不假报 ready）。
	 */
	private Mono<CreateResult> convergeFailed(CommandRow command, JobRow job, Throwable error) {
		return Mono.defer(() -> projects.markStatus(job.projectId(), "provisioning_failed")
				.then(jobs.updateState(job.id(), "failed", "hypit_backend_unavailable",
						error.getMessage() == null ? "provision failed" : error.getMessage()))
				.then(events.append(job.id(), "terminal",
						HypitJson.write(Map.of("state", "failed", "reason", "provision_failed"))))
				.then(commands.saveResult(command.id(), "failed",
						HypitJson.write(Map.of("projectId", job.projectId().toString(), "jobId", job.id().toString(),
								"state", "provisioning_failed"))))
				.then(projects.findOwned(command.accountId(), job.projectId()))
				.map(project -> new CreateResult(toDto(project), job.id(), "failed"))
				.switchIfEmpty(Mono.error(new IllegalStateException("project vanished after provision failure")))
				.as(transactions::transactional));
	}

	// ------------------------------------------------------------------
	// 读 / 改 / 删（04.8）
	// ------------------------------------------------------------------

	public Mono<Project> get(String accountId, UUID projectId) {
		return projects.findOwned(accountId, projectId).switchIfEmpty(Mono.error(notFound()))
				.map(HypitProjectService::toDto);
	}

	/** 列表（§6.1：默认 20、最大 100）。 */
	public Mono<List<Project>> list(String accountId, int limit) {
		return projects.listOwnedPage(accountId, Math.min(Math.max(limit, 1), 100))
				.map(rows -> rows.stream().map(HypitProjectService::toDto).toList());
	}

	public Mono<Project> patchTitle(String accountId, UUID projectId, String title, Long baseVersion) {
		String normalized = title == null ? "" : title.trim();
		if (normalized.isEmpty() || normalized.length() > 60) {
			return Mono.error(invalid("标题须为 1–60 字。"));
		}
		if (baseVersion == null) {
			return Mono.error(invalid("baseVersion 必填（metadata version CAS）。"));
		}
		return projects.findOwned(accountId, projectId).switchIfEmpty(Mono.error(notFound()))
				.flatMap(existing -> projects.patchTitle(projectId, accountId, normalized, baseVersion)
						.switchIfEmpty(Mono.error(
								new IntelligenceException(409, "hypit_revision_conflict", "工程元数据已被并发修改，请刷新后重试。")))
						.map(HypitProjectService::toDto));
	}

	/** 删除：活跃工作 409；先标 deleting，物理清理交 sidecar（§5.3/04.8）。 */
	public Mono<Void> delete(String accountId, UUID projectId) {
		return projects.findOwned(accountId, projectId).switchIfEmpty(Mono.error(notFound())).flatMap(project -> {
			if ("deleted".equals(project.status())) {
				return Mono.empty();
			}
			return jobs.hasActiveWork(projectId).flatMap(active -> {
				if (active) {
					return Mono.error(new IntelligenceException(409, "hypit_active_work", "工程存在活跃任务，请先取消后再删除。"));
				}
				Mono<Void> physical = properties.enabled()
						? sidecar.commandAsync("java-delete-" + projectId, "workspace.delete",
								Map.of("projectId", projectId.toString())).then()
						: Mono.empty();
				return projects.markStatus(projectId, "deleting").then(physical)
						.then(projects.markStatus(projectId, "deleted")).then();
			});
		});
	}

	/** 文件树 / 单文件（sidecar workspace.files / workspace.read；仅 owner + enabled）。 */
	public Mono<Object> files(String accountId, UUID projectId) {
		return ownedEnabled(accountId, projectId)
				.then(sidecar.commandAsync("java-files-" + projectId + "-" + System.currentTimeMillis(),
						"workspace.files", Map.of("projectId", projectId.toString())))
				.map(SidecarCommand::result);
	}

	public Mono<Object> file(String accountId, UUID projectId, String path) {
		if (path == null || path.isBlank()) {
			return Mono.error(invalid("path 必填。"));
		}
		return ownedEnabled(accountId, projectId)
				.then(sidecar.commandAsync("java-read-" + projectId + "-" + System.currentTimeMillis(),
						"workspace.read", Map.of("projectId", projectId.toString(), "path", path)))
				.map(SidecarCommand::result);
	}

	private Mono<HypitProjectRepository.ProjectRow> ownedEnabled(String accountId, UUID projectId) {
		return projects.findOwned(accountId, projectId).switchIfEmpty(Mono.error(notFound())).flatMap(
				project -> properties.enabled() ? Mono.just(project) : Mono.error(HypitAccessService.disabled()));
	}

	static Project toDto(HypitProjectRepository.ProjectRow row) {
		return new Project(row.id().toString(), row.accountId(), row.title(), row.mode(), row.status(), row.revision(),
				row.version(), row.selectedRun(), null, row.createdAt().toString(), row.updatedAt().toString());
	}
}
