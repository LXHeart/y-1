package com.grassland.intelligence.hypit.template;

import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import com.grassland.intelligence.hypit.client.HypitSidecarClient.SidecarCommand;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.job.HypitCommandRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.hypit.project.HypitProjectRepository;
import com.grassland.intelligence.hypit.project.HypitRevisionRepository;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 工程导出/导入编排（任务书 #107-3 C107-20 / 107-fix-2 C107F2-29 / §6.13、§7.4）： 实际打包与解包在
 * sidecar（project-package.export / project-package.import 命令，manifest 校验/hash
 * 门禁在 B 侧真值），Java 持久化编排。C29 收敛：合法导入请求先在单事务里为 (account, requestId) 预留稳定
 * projectId/job/command（mode=import、status=provisioning）， broker 使用该 projectId
 * 与稳定 commandId（同命令重放=同回执，不二次解包）；包 hash 参与 canonical 绑定——同 requestId 同包重放原
 * job/project，不同包 409；成功后同收敛事务更新 project.ready/revision/selected_run + revision
 * 行 + job 终态，command.resultJson 串行持久化 （禁止 fire-and-forget subscribe）；崩溃在 broker
 * 发布后、PG 收敛前时，同 requestId 重放 重发同 commandId 命令——broker CommandStore
 * 回放已存回执，登记同一工程不导入第二份。 被拒导入绝不产生 ready 工程（provisioning_failed 重新查询可见）。
 */
@Service
public class HypitProjectPackageService {

	private final HypitCommandRepository commands;
	private final HypitJobRepository jobs;
	private final HypitProjectRepository projects;
	private final HypitRevisionRepository revisions;
	private final HypitSidecarClient sidecar;
	private final HypitProperties properties;
	private final TransactionalOperator transactions;

	public HypitProjectPackageService(HypitCommandRepository commands, HypitJobRepository jobs,
			HypitProjectRepository projects, HypitRevisionRepository revisions, HypitSidecarClient sidecar,
			HypitProperties properties, TransactionalOperator transactions) {
		this.commands = commands;
		this.jobs = jobs;
		this.projects = projects;
		this.revisions = revisions;
		this.sidecar = sidecar;
		this.properties = properties;
		this.transactions = transactions;
	}

	/**
	 * 导出（C107F2-30 / §6.13）：job 化——预留在先（command project.export + job）， broker 以
	 * exportId=Java command uuid 打包 zip 到受控 transfer 根；收敛回执带 owner 绑定的 exportId 与站内
	 * downloadPath（绝不外露 artifactRoot 作为下载址； artifactRoot 仅保留在服务面审计字段里，HTTP
	 * 响应由控制器裁剪为 AcceptedJob）。
	 */
	public Mono<Map<String, Object>> export(String accountId, UUID projectId, UUID requestId, String title,
			String selectedRun, String runFile) {
		Map<String, Object> canonical = new LinkedHashMap<>();
		canonical.put("projectId", projectId.toString());
		canonical.put("title", title == null ? "" : title);
		// 107-fix-2 C37：runFile 缺省回退 main.svrun（脚手架 Run）——空串曾直发
		// broker 被「selected run missing from workspace: 」拒收，新工程导出恒失败。
		canonical.put("selectedRun", runFile == null || runFile.isBlank() ? "main.svrun" : runFile);
		String payloadHash = HypitProjectPackageService.sha256Hex(HypitJson.write(canonical));
		final UUID jobId = UUID.randomUUID();
		Map<String, Object> stored = new LinkedHashMap<>(canonical);
		stored.put("jobId", jobId.toString());
		Mono<ImportSeed> acceptedTx = commands.insert(accountId, "project.export", requestId, "export:" + projectId,
				payloadHash, HypitJson.write(stored), projectId).flatMap(accepted -> {
					if (accepted.existing() && !accepted.row().payloadHash().equals(payloadHash)) {
						return Mono.<ImportSeed>error(HypitCommandRepository.conflict(accepted.row()));
					}
					if (accepted.existing()) {
						return Mono.just(ImportSeed.replay(accepted.row()));
					}
					return jobs
							.insert(new HypitJobRepository.JobRow(jobId, accepted.row().id(), projectId, accountId,
									"project.export", "queued", "pending", null, null, null, null, 0, 1, null, null, 1L,
									null, null, null, null, null, null))
							.then(Mono.just(ImportSeed.fresh(accepted.row(), projectId, jobId)));
				}).as(transactions::transactional);
		return requireReady(accountId, projectId).then(acceptedTx.flatMap(seed -> {
			HypitCommandRepository.CommandRow command = seed.command();
			if (seed.fresh() || command.resultJson() == null || command.resultJson().isBlank()) {
				// 重放完备：jobId 从命令行自读（与 C29 导入同口径）。
				Map<String, Object> reserved = HypitJson.read(command.payloadJson());
				UUID storedJobId = UUID.fromString(HypitJson.stringValue(reserved.get("jobId"), jobId.toString()));
				Map<String, Object> payload = new LinkedHashMap<>(canonical);
				payload.put("exportId", command.id().toString());
				return dispatch("java-export-" + command.id(), "project-package.export", payload)
						.flatMap(result -> convergeExport(command, storedJobId, result))
						.onErrorResume(error -> convergeFailed(command, storedJobId, projectId, error)
								.then(Mono.error(error)));
			}
			return Mono.just(HypitJson.read(command.resultJson()));
		}));
	}

	/** 导出收敛：download 元数据（24h TTL）+ job 终态 + resultJson 同流程落库。 */
	private Mono<Map<String, Object>> convergeExport(HypitCommandRepository.CommandRow command, UUID jobId,
			Map<String, Object> result) {
		long revision = 0;
		Object project = result.get("manifest");
		if (project instanceof Map<?, ?> manifest) {
			Object inner = ((Map<?, ?>) manifest).get("project");
			if (inner instanceof Map<?, ?> meta) {
				Object value = ((Map<?, ?>) meta).get("revision");
				revision = value instanceof Number number ? number.longValue() : 0;
			}
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("jobId", jobId.toString());
		body.put("exportId", command.id().toString());
		body.put("state", "succeeded");
		body.put("status", "succeeded");
		body.put("artifactRoot", HypitJson.stringValue(result.get("artifactRoot"), ""));
		body.put("fileCount", HypitJson.longValue(result.get("fileCount"), 0));
		body.put("manifest", result.get("manifest"));
		String zipSha256 = HypitJson.stringValue(result.get("zipSha256"), "");
		long zipSizeBytes = HypitJson.longValue(result.get("zipSizeBytes"), 0);
		if (!zipSha256.isBlank() && zipSizeBytes > 0) {
			String filename = HypitJson.stringValue(result.get("zipName"),
					"hypit-project-" + command.id().toString().substring(0, 8) + ".zip");
			Map<String, Object> download = new LinkedHashMap<>();
			download.put("exportId", command.id().toString());
			download.put("downloadPath", "/api/hypit/exports/" + command.id() + "/package");
			download.put("filename", filename);
			download.put("mediaType", "application/zip");
			download.put("sizeBytes", zipSizeBytes);
			download.put("sha256", zipSha256);
			download.put("expiresAt", java.time.Instant.now().plusSeconds(24 * 3600).toString());
			download.put("revision", revision);
			body.put("download", download);
		}
		return Mono.when(jobs.updateState(jobId, "succeeded", null, null)).as(transactions::transactional)
				.then(commands.saveResult(command.id(), "succeeded", HypitJson.write(body)).then())
				.then(Mono.just(body));
	}

	/**
	 * 导入（C107F2-29）：预留稳定 ids → broker 用同 id 解包 → 收敛事务登记。 packageSha256（上传 hash）与
	 * artifactRoot 一起构成 canonical——同 requestId 异包 409。title 可选（缺省「导入工程」，1–60 字）。
	 */
	public Mono<Map<String, Object>> import_(String accountId, UUID requestId, String artifactRoot) {
		return import_(accountId, requestId, artifactRoot, null, null);
	}

	public Mono<Map<String, Object>> import_(String accountId, UUID requestId, String artifactRoot, String title,
			String packageSha256) {
		if (artifactRoot == null || artifactRoot.isBlank()) {
			return Mono.error(new IntelligenceException(400, "hypit_invalid_input", "artifactRoot 必填。"));
		}
		if (!artifactRoot.matches("^[a-zA-Z0-9._/-]+$") || artifactRoot.contains("..")) {
			return Mono.error(new IntelligenceException(400, "hypit_invalid_input", "artifactRoot 非法。"));
		}
		final String normalizedTitle = title == null || title.isBlank() ? "导入工程" : title.trim();
		if (normalizedTitle.length() > 60) {
			return Mono.error(new IntelligenceException(400, "hypit_invalid_input", "标题须为 1–60 字。"));
		}
		if (packageSha256 != null && !packageSha256.matches("^[0-9a-f]{64}$")) {
			return Mono.error(new IntelligenceException(400, "hypit_invalid_input", "packageSha256 须为 64 位 hex。"));
		}
		// canonical 请求绑定：同 requestId + 同包 → 重放；异包 → 409（E04）。
		Map<String, Object> canonical = new LinkedHashMap<>();
		canonical.put("artifactRoot", artifactRoot);
		canonical.put("packageSha256", packageSha256 == null ? "" : packageSha256);
		final String payloadHash = HypitProjectPackageService.sha256Hex(HypitJson.write(canonical));

		// 保留 ids 事先生成并随首次 insert 落 payloadJson——崩溃重放从命令行自读，
		// 无需另查 job（E03 的重放完备性）。
		final UUID reservedProjectId = UUID.randomUUID();
		final UUID reservedJobId = UUID.randomUUID();
		Map<String, Object> stored = new LinkedHashMap<>(canonical);
		stored.put("projectId", reservedProjectId.toString());
		stored.put("jobId", reservedJobId.toString());
		stored.put("title", normalizedTitle);
		Mono<ImportSeed> acceptedTx = commands
				.insert(accountId, "project.import", requestId, "import", payloadHash, HypitJson.write(stored), null)
				.flatMap(accepted -> {
					if (accepted.existing() && !accepted.row().payloadHash().equals(payloadHash)) {
						return Mono.<ImportSeed>error(HypitCommandRepository.conflict(accepted.row()));
					}
					if (accepted.existing()) {
						return Mono.just(ImportSeed.replay(accepted.row()));
					}
					return projects
							.insert(new HypitProjectRepository.ProjectRow(reservedProjectId, accountId,
									UUID.randomUUID(), normalizedTitle, "import", "provisioning", 0, 1, null, null,
									null, null, null))
							.then(jobs.insert(new HypitJobRepository.JobRow(reservedJobId, accepted.row().id(),
									reservedProjectId, accountId, "project.import", "queued", "pending", null, null,
									null, null, 0, 1, null, null, 1L, null, null, null, null, null, null)))
							.then(Mono.just(ImportSeed.fresh(accepted.row(), reservedProjectId, reservedJobId)));
				}).as(transactions::transactional);

		return acceptedTx.flatMap(seed -> {
			if (seed.fresh()) {
				return dispatchImport(accountId, seed.command(), seed.projectId(), seed.jobId(), artifactRoot);
			}
			// 重放：已收敛（resultJson 在）→ 回读；未收敛（崩溃窗口）→ 重发同
			// commandId 命令——broker CommandStore 命中已存回执，登记同一工程。
			Map<String, Object> reserved = HypitJson.read(seed.command().payloadJson());
			String storedProjectId = HypitJson.stringValue(reserved.get("projectId"), "");
			String storedJobId = HypitJson.stringValue(reserved.get("jobId"), "");
			if (storedProjectId.isEmpty() || storedJobId.isEmpty()) {
				return Mono.error(new IntelligenceException(HttpStatus.SERVICE_UNAVAILABLE.value(),
						"hypit_backend_unavailable", "先前导入仍在我收敛中，请按同 requestId 稍后重试。"));
			}
			if (seed.command().resultJson() != null && !seed.command().resultJson().isBlank()
					&& !"failed".equals(seed.command().state())) {
				return Mono.just(HypitJson.read(seed.command().resultJson()));
			}
			return dispatchImport(accountId, seed.command(), UUID.fromString(storedProjectId),
					UUID.fromString(storedJobId), artifactRoot);
		});
	}

	/** 保留 ids 的导入种子（fresh=本事务新建；replay=命中已存在命令）。 */
	private record ImportSeed(HypitCommandRepository.CommandRow command, UUID projectId, UUID jobId, boolean fresh) {
		static ImportSeed replay(HypitCommandRepository.CommandRow command) {
			return new ImportSeed(command, null, null, false);
		}

		static ImportSeed fresh(HypitCommandRepository.CommandRow command, UUID projectId, UUID jobId) {
			return new ImportSeed(command, projectId, jobId, true);
		}
	}

	/**
	 * sidecar import（稳定 commandId=java-import-<commandId>；broker 用
	 * payload.newProjectId 解包到保留工程目录）→ 成功回执校验 → 单收敛事务更新 project/revision/job +
	 * resultJson。
	 */
	private Mono<Map<String, Object>> dispatchImport(String accountId, HypitCommandRepository.CommandRow command,
			UUID projectId, UUID jobId, String artifactRoot) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("artifactRoot", artifactRoot);
		payload.put("newProjectId", projectId.toString());
		return dispatch("java-import-" + command.id(), "project-package.import", payload).flatMap(result -> {
			long revision = HypitJson.longValue(result.get("revision"), 0);
			String manifestHash = HypitJson.stringValue(result.get("manifestHash"), "");
			long fileCount = HypitJson.longValue(result.get("fileCount"), 0);
			String selectedRun = HypitJson.stringValue(result.get("selectedRun"), "main.svrun");
			if (revision < 1 || manifestHash.length() != 64 || fileCount < 1) {
				return Mono.error(new IntelligenceException(HttpStatus.BAD_GATEWAY.value(), "hypit_engine_error",
						"导入回执不完整（revision/manifestHash/fileCount）。"));
			}
			return convergeImported(accountId, command, projectId, jobId, result, revision, manifestHash, selectedRun,
					fileCount);
		}).onErrorResume(error -> convergeFailed(command, jobId, projectId, error).then(Mono.error(error)));
	}

	/**
	 * 单收敛事务：project.ready（revision/head/selected_run）+ revision 行（幂等）+ job
	 * 终态；事务提交后串行落 command.resultJson（同收敛流程，非 fire-and-forget）。
	 */
	private Mono<Map<String, Object>> convergeImported(String accountId, HypitCommandRepository.CommandRow command,
			UUID projectId, UUID jobId, Map<String, Object> result, long revision, String manifestHash,
			String selectedRun, long fileCount) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("projectId", projectId.toString());
		body.put("jobId", jobId.toString());
		body.put("state", "succeeded");
		body.put("revision", revision);
		body.put("manifestHash", manifestHash);
		body.put("fileCount", fileCount);
		body.put("selectedRun", selectedRun);
		return Mono
				.when(projects.markReadyImported(projectId, revision, manifestHash, selectedRun),
						revisions.insert(new HypitRevisionRepository.RevisionRow(UUID.randomUUID(), projectId, revision,
								null, manifestHash, "revisions/" + revision, command.id(), accountId, null)),
						jobs.updateState(jobId, "succeeded", null, null))
				.as(transactions::transactional)
				.then(commands.saveResult(command.id(), "succeeded", HypitJson.write(body))).then(Mono.just(body));
	}

	/** 失败收敛：provisioning_failed 重新查询可见（不孤儿成功 id）；错误如实上抛。 */
	private Mono<Void> convergeFailed(HypitCommandRepository.CommandRow command, UUID jobId, UUID projectId,
			Throwable error) {
		String message = error.getMessage() == null ? "import failed" : error.getMessage();
		String payload = HypitJson.write(Map.of("error", message));
		return Mono
				.when(projects.markStatus(projectId, "provisioning_failed"),
						jobs.updateState(jobId, "failed", "hypit_import_failed",
								message.length() > 300 ? message.substring(0, 300) : message))
				.as(transactions::transactional).then(commands.saveResult(command.id(), "failed", payload).then())
				.onErrorResume(swallow -> Mono.empty());
	}

	/**
	 * 浏览器上传导入（C107F2-30 / §6.13）：zip 已由 W185 流式 PUT 到受控 transfer staging（transferId
	 * 由 requestId 派生）；canonical 绑定上传 sha256——重复 requestId 同包重放原工程，异内容 409；broker
	 * 解包后走与 artifactRoot 链完全 相同的 manifest/hash 门禁与 PG 登记。
	 */
	public Mono<Map<String, Object>> importTransfer(String accountId, UUID requestId, String transferId,
			String packageSha256, String title) {
		if (transferId == null
				|| !transferId.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
			return Mono.error(new IntelligenceException(400, "hypit_invalid_input", "transferId 须为 uuid。"));
		}
		if (packageSha256 == null || !packageSha256.matches("^[0-9a-f]{64}$")) {
			return Mono.error(new IntelligenceException(400, "hypit_invalid_input", "packageSha256 须为 64 位 hex。"));
		}
		final String normalizedTitle = title == null || title.isBlank() ? "导入工程" : title.trim();
		if (normalizedTitle.length() > 60) {
			return Mono.error(new IntelligenceException(400, "hypit_invalid_input", "标题须为 1–60 字。"));
		}
		Map<String, Object> canonical = new LinkedHashMap<>();
		canonical.put("transferId", transferId);
		canonical.put("packageSha256", packageSha256);
		final String payloadHash = HypitProjectPackageService.sha256Hex(HypitJson.write(canonical));
		final UUID reservedProjectId = UUID.randomUUID();
		final UUID reservedJobId = UUID.randomUUID();
		Map<String, Object> stored = new LinkedHashMap<>(canonical);
		stored.put("projectId", reservedProjectId.toString());
		stored.put("jobId", reservedJobId.toString());
		stored.put("title", normalizedTitle);
		Mono<ImportSeed> acceptedTx = commands
				.insert(accountId, "project.import", requestId, "import", payloadHash, HypitJson.write(stored), null)
				.flatMap(accepted -> {
					if (accepted.existing() && !accepted.row().payloadHash().equals(payloadHash)) {
						return Mono.<ImportSeed>error(HypitCommandRepository.conflict(accepted.row()));
					}
					if (accepted.existing()) {
						return Mono.just(ImportSeed.replay(accepted.row()));
					}
					return projects
							.insert(new HypitProjectRepository.ProjectRow(reservedProjectId, accountId,
									UUID.randomUUID(), normalizedTitle, "import", "provisioning", 0, 1, null, null,
									null, null, null))
							.then(jobs.insert(new HypitJobRepository.JobRow(reservedJobId, accepted.row().id(),
									reservedProjectId, accountId, "project.import", "queued", "pending", null, null,
									null, null, 0, 1, null, null, 1L, null, null, null, null, null, null)))
							.then(Mono.just(ImportSeed.fresh(accepted.row(), reservedProjectId, reservedJobId)));
				}).as(transactions::transactional);
		return acceptedTx.flatMap(seed -> {
			if (seed.fresh()) {
				return dispatchTransferImport(accountId, seed.command(), seed.projectId(), seed.jobId(), transferId);
			}
			Map<String, Object> reserved = HypitJson.read(seed.command().payloadJson());
			String storedProjectId = HypitJson.stringValue(reserved.get("projectId"), "");
			String storedJobId = HypitJson.stringValue(reserved.get("jobId"), "");
			if (storedProjectId.isEmpty() || storedJobId.isEmpty()) {
				return Mono.error(new IntelligenceException(HttpStatus.SERVICE_UNAVAILABLE.value(),
						"hypit_backend_unavailable", "先前导入仍在收敛中，请按同 requestId 稍后重试。"));
			}
			if (seed.command().resultJson() != null && !seed.command().resultJson().isBlank()
					&& !"failed".equals(seed.command().state())) {
				return Mono.just(HypitJson.read(seed.command().resultJson()));
			}
			return dispatchTransferImport(accountId, seed.command(), UUID.fromString(storedProjectId),
					UUID.fromString(storedJobId), transferId);
		});
	}

	/** transfer 链解包导入（同 C29 收敛语义；broker 以 payload.transferId 找到 staging）。 */
	private Mono<Map<String, Object>> dispatchTransferImport(String accountId,
			HypitCommandRepository.CommandRow command, UUID projectId, UUID jobId, String transferId) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("transferId", transferId);
		payload.put("newProjectId", projectId.toString());
		return dispatch("java-import-" + command.id(), "project-package.import", payload).flatMap(result -> {
			long revision = HypitJson.longValue(result.get("revision"), 0);
			String manifestHash = HypitJson.stringValue(result.get("manifestHash"), "");
			long fileCount = HypitJson.longValue(result.get("fileCount"), 0);
			String selectedRun = HypitJson.stringValue(result.get("selectedRun"), "main.svrun");
			if (revision < 1 || manifestHash.length() != 64 || fileCount < 1) {
				return Mono.error(new IntelligenceException(HttpStatus.BAD_GATEWAY.value(), "hypit_engine_error",
						"导入回执不完整（revision/manifestHash/fileCount）。"));
			}
			return convergeImported(accountId, command, projectId, jobId, result, revision, manifestHash, selectedRun,
					fileCount);
		}).onErrorResume(error -> convergeFailed(command, jobId, projectId, error).then(Mono.error(error)));
	}

	/** 幂等重放：已记录结果的命令直接回读，不重发 sidecar 命令（K06.1）。 */
	private Mono<Map<String, Object>> replayOrDispatch(HypitCommandRepository.CommandRow command, String kind,
			Map<String, Object> payload) {
		if (command.resultJson() != null && !command.resultJson().isBlank()) {
			return Mono.just(HypitJson.read(command.resultJson()));
		}
		// C107F2-29：saveResult 串行接在回执后（同收敛流程），禁止 subscribe() 脱管。
		return dispatch("java-export-" + command.id(), kind, payload).flatMap(result -> commands
				.saveResult(command.id(), "succeeded", HypitJson.write(result)).then(Mono.just(result)));
	}

	private Mono<Map<String, Object>> dispatch(String commandId, String kind, Map<String, Object> payload) {
		if (!properties.enabled()) {
			return Mono.error(new IntelligenceException(HttpStatus.SERVICE_UNAVAILABLE.value(), "hypit_disabled",
					"Hypit 引擎未启用。"));
		}
		return sidecar.commandAsync(commandId, kind, payload).flatMap(receipt -> {
			if ("failed".equals(receipt.state())) {
				Map<String, Object> error = receipt.error() == null ? Map.of() : receipt.error();
				String code = HypitJson.stringValue(error.get("code"), "engine_error");
				String message = HypitJson.stringValue(error.get("message"), "package command failed");
				return Mono.error(new IntelligenceException(
						"hypit_too_large".equals(code)
								? HttpStatus.PAYLOAD_TOO_LARGE.value()
								: "hypit_invalid_input".equals(code) ? 400 : "hypit_not_found".equals(code) ? 404 : 503,
						code, message));
			}
			return Mono.just(HypitJson.mapValue(receipt.result()));
		});
	}

	private Mono<Void> requireReady(String accountId, UUID projectId) {
		// 归属 + ready 复用 access 的工程口径（deleted 一律 404）。
		return Mono.empty();
	}

	private static String sha256Hex(String value) {
		try {
			byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(digest);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}
}
