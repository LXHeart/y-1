package com.grassland.intelligence.hypit.project;

import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import com.grassland.intelligence.hypit.client.HypitSidecarClient.SidecarCommand;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.job.HypitCommandRepository;
import com.grassland.intelligence.hypit.job.HypitCommandRepository.CommandRow;
import com.grassland.intelligence.hypit.job.HypitJobEventRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository;
import com.grassland.intelligence.hypit.security.HypitAccessService;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 变更集服务（任务书 #107-1 C107-04 / K06.3.8 / TC107-04-02）。
 *
 * <p>
 * 两种 applyMode 语义分开：{@code save} 允许坏语法（head 照常推进，诊断随响应返回）； {@code validated} 必须
 * checkPassed——sidecar 在临时副本上跑真实 check，失败保留草稿、 不移动 head。baseRevision CAS 冲突 409
 * 且草稿保留（state=conflict 可重新基于新 head 提交）。
 */
@Service
public class HypitChangesetService {

	private final DatabaseClient db;
	private final HypitProjectRepository projects;
	private final HypitRevisionRepository revisions;
	private final HypitCommandRepository commands;
	private final HypitJobRepository jobs;
	private final HypitJobEventRepository events;
	private final HypitSidecarClient sidecar;
	private final HypitProperties properties;
	private final TransactionalOperator transactions;

	public HypitChangesetService(DatabaseClient db, HypitProjectRepository projects, HypitRevisionRepository revisions,
			HypitCommandRepository commands, HypitJobRepository jobs, HypitJobEventRepository events,
			HypitSidecarClient sidecar, HypitProperties properties, TransactionalOperator transactions) {
		this.db = db;
		this.projects = projects;
		this.revisions = revisions;
		this.commands = commands;
		this.jobs = jobs;
		this.events = events;
		this.sidecar = sidecar;
		this.properties = properties;
		this.transactions = transactions;
	}

	public record FileChange(String path, String action, String content, String baseHash) {
	}

	public record ChangesetRow(UUID id, UUID projectId, UUID commandId, long baseRevision, String applyMode,
			String changeManifestHandle, String checkStatus, String state, Long appliedRevision) {
	}

	private static final String COLS = """
			id::text, project_id::text, command_id::text, base_revision, apply_mode,
			change_manifest_handle, check_status, state, applied_revision
			""";

	private static IntelligenceException invalid(String message) {
		return new IntelligenceException(400, "hypit_invalid_input", message);
	}

	private static IntelligenceException notFound() {
		return new IntelligenceException(404, "hypit_not_found", "资源不存在。");
	}

	private static IntelligenceException conflict() {
		return new IntelligenceException(409, "hypit_revision_conflict", "基线修订已过期，草稿已保留，请基于新 head 重新提交。");
	}

	private Mono<HypitProjectRepository.ProjectRow> ownedProjectRow(String accountId, UUID projectId) {
		return projects.findOwned(accountId, projectId).switchIfEmpty(Mono.error(notFound()))
				.flatMap(project -> "ready".equals(project.status())
						? Mono.just(project)
						: Mono.<HypitProjectRepository.ProjectRow>error(new IntelligenceException(409,
								"hypit_state_conflict", "工程当前状态不可编辑：" + project.status())));
	}

	private Mono<Void> ownedProject(String accountId, UUID projectId) {
		return ownedProjectRow(accountId, projectId).then();
	}

	/**
	 * 创建草稿：不接触 head。change manifest 以 canonical JSON 摘要作为 handle（内容本体存
	 * command.payload_json，可审计）。
	 */
	public Mono<ChangesetRow> create(String accountId, UUID projectId, UUID requestId, long baseRevision,
			String applyMode, List<FileChange> changes) {
		if (requestId == null) {
			return Mono.error(invalid("requestId 必填。"));
		}
		if (!"save".equals(applyMode) && !"validated".equals(applyMode)) {
			return Mono.error(invalid("applyMode 必须是 save 或 validated。"));
		}
		if (changes == null || changes.isEmpty()) {
			return Mono.error(invalid("changes 不能为空。"));
		}
		for (FileChange change : changes) {
			if (change.path() == null || change.path().isBlank() || change.action() == null
					|| (!"put".equals(change.action()) && !"delete".equals(change.action()))) {
				return Mono.error(invalid("每个变更须含 path 与 put/delete action。"));
			}
			if ("put".equals(change.action()) && change.content() == null) {
				return Mono.error(invalid("put 变更缺少 content：" + change.path()));
			}
		}
		String canonical = HypitJson.write(Map.of("projectId", projectId.toString(), "baseRevision", baseRevision,
				"applyMode", applyMode, "changes", changes));
		String payloadHash = HypitProjectService.sha256Hex(canonical);
		UUID changesetId = UUID.randomUUID();
		return ownedProject(accountId, projectId)
				.then(commands
						.insert(accountId, "changeset.create", requestId, "changeset:" + projectId, payloadHash,
								"{\"canonical\":" + HypitJson.write(canonical) + "}", projectId)
						.flatMap(accepted -> accepted.existing() && !accepted.row().payloadHash().equals(payloadHash)
								? Mono.<CommandRow>error(HypitCommandRepository.conflict(accepted.row()))
								: Mono.just(accepted.row())))
				.flatMap(command -> insertChangeset(changesetId, projectId, command.id(), baseRevision, applyMode,
						payloadHash))
				.as(transactions::transactional).flatMap(
						row -> "validated".equals(applyMode)
								? withChanges(row,
										frozen -> checkDraft(projectId, row)
												.flatMap(passed -> persistCheck(row, passed)))
								: Mono.just(row));
	}

	/** 响应式取出草稿冻结的变更内容（block() 不得出现在事件循环上）。 */
	private <T> Mono<T> withChanges(ChangesetRow row,
			java.util.function.Function<List<Map<String, Object>>, Mono<T>> consumer) {
		return commands.findById(row.commandId()).map(command -> {
			Map<String, Object> payload = HypitJson.read(command.payloadJson());
			String canonicalText = HypitJson.stringValue(payload.get("canonical"), "null");
			Map<String, Object> parsed = HypitJson.read(canonicalText);
			Object changes = parsed.get("changes");
			return changes instanceof List<?> list
					? list.stream().map(HypitJson::mapValue).toList()
					: List.<Map<String, Object>>of();
		}).flatMap(consumer);
	}

	private Mono<ChangesetRow> insertChangeset(UUID changesetId, UUID projectId, UUID commandId, long baseRevision,
			String applyMode, String manifestHandle) {
		return db
				.sql("INSERT INTO hypit_changeset(id, project_id, command_id, base_revision, apply_mode,"
						+ " change_manifest_handle, check_status, state) VALUES (CAST(:id AS uuid),"
						+ " CAST(:project AS uuid), CAST(:command AS uuid), :baseRevision, :applyMode,"
						+ " :manifest, 'not_checked', 'draft') ON CONFLICT (command_id) DO NOTHING" + " RETURNING "
						+ COLS)
				.bind("id", changesetId.toString()).bind("project", projectId.toString())
				.bind("command", commandId.toString()).bind("baseRevision", baseRevision).bind("applyMode", applyMode)
				.bind("manifest", manifestHandle).map(HypitChangesetService::mapRow).one()
				.switchIfEmpty(Mono.defer(() -> findByCommand(commandId)));
	}

	/** validated 草稿的真实 check（sidecar 引擎，不经任何生成 Provider）。 */
	private Mono<Boolean> checkDraft(UUID projectId, ChangesetRow row) {
		if (!properties.enabled()) {
			return Mono.just(false);
		}
		return withChanges(row, changes -> {
			Map<String, Object> payload = Map.of("projectId", projectId.toString(), "commandId",
					row.commandId() == null ? "" : row.commandId().toString(), "baseRevision", row.baseRevision(),
					"applyMode", "validated", "changes", changes);
			return sidecar.commandAsync("java-check-" + row.id(), "workspace.check", payload).map(receipt -> {
				if ("failed".equals(receipt.state())) {
					return false;
				}
				Map<String, Object> result = HypitJson.mapValue(receipt.result());
				return Boolean.TRUE.equals(result.get("ok"));
			}).onErrorReturn(false);
		});
	}

	private Mono<ChangesetRow> persistCheck(ChangesetRow row, boolean passed) {
		return db
				.sql("UPDATE hypit_changeset SET check_status = :status, updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid) RETURNING " + COLS)
				.bind("id", row.id().toString()).bind("status", passed ? "passed" : "failed")
				.map(HypitChangesetService::mapRow).one();
	}

	/**
	 * 应用：sidecar K06.3 journal 原子提交新 revision；Java 收敛 changeset/revision/project。
	 * CAS 冲突 409、validated 失败 422，草稿都保留。
	 */
	public Mono<ApplyResult> apply(String accountId, UUID projectId, UUID changesetId, UUID requestId,
			long baseRevision) {
		if (requestId == null) {
			return Mono.error(invalid("requestId 必填。"));
		}
		return ownedProjectRow(accountId, projectId).flatMap(project -> findById(changesetId).flatMap(row -> {
			if (!"draft".equals(row.state()) && !"conflict".equals(row.state())) {
				return Mono.error(new IntelligenceException(409, "hypit_state_conflict", "变更集状态不可应用：" + row.state()));
			}
			if ("validated".equals(row.applyMode()) && !"passed".equals(row.checkStatus())) {
				return Mono.error(new IntelligenceException(422, "hypit_compile_failed", "validated 变更集未通过检查，草稿保留。"));
			}
			if (row.baseRevision() != baseRevision || project.revision() != baseRevision) {
				// CAS 双闸：请求=变更集基准=工程当前 head，旧基准一律 409。
				return Mono.error(conflict());
			}
			return dispatchApply(accountId, projectId, row, requestId);
		}));
	}

	public record ApplyResult(long revision, String manifestHash, List<String> appliedPaths) {
	}

	private Mono<ApplyResult> dispatchApply(String accountId, UUID projectId, ChangesetRow row, UUID requestId) {
		return withChanges(row, changes -> {
			String canonical = HypitJson.write(Map.of("changesetId", row.id().toString(), "baseRevision",
					row.baseRevision(), "applyMode", row.applyMode(), "changes", changes));
			String payloadHash = HypitProjectService.sha256Hex(canonical);
			return commands
					.insert(accountId, "changeset.apply", requestId, "changeset:" + row.id(), payloadHash,
							"{\"canonical\":" + HypitJson.write(canonical) + "}", projectId)
					.flatMap(accepted -> accepted.existing() && !accepted.row().payloadHash().equals(payloadHash)
							? Mono.<CommandRow>error(HypitCommandRepository.conflict(accepted.row()))
							: Mono.just(accepted.row()))
					.flatMap(command -> properties.enabled()
							? sidecar.commandAsync(command.id().toString(), "workspace.apply",
									Map.of("projectId", projectId.toString(), "commandId", command.id().toString(),
											"baseRevision", row.baseRevision(), "applyMode", row.applyMode(), "changes",
											changes))
							: Mono.<SidecarCommand>error(
									new IntelligenceException(503, "hypit_disabled", "Hypit 引擎未启用。")))
					.flatMap(receipt -> interpretApply(receipt))
					.flatMap(receipt -> convergeApplied(projectId, row, receipt))
					.onErrorResume(error -> error instanceof IntelligenceException intelligenceError
							&& "hypit_revision_conflict".equals(intelligenceError.code())
									? markConflict(row).then(Mono.error(error))
									: Mono.error(error));
		});
	}

	/** sidecar 回执解释：失败行的 error.code 贯通为契约错误码。 */
	private Mono<SidecarCommand> interpretApply(SidecarCommand receipt) {
		if (!"failed".equals(receipt.state())) {
			return Mono.just(receipt);
		}
		String code = receipt.error() == null
				? "engine_error"
				: HypitJson.stringValue(receipt.error().get("code"), "engine_error");
		String message = receipt.error() == null
				? "sidecar apply failed"
				: HypitJson.stringValue(receipt.error().get("message"), "sidecar apply failed");
		return "revision_conflict".equals(code)
				? Mono.error(new IntelligenceException(409, "hypit_revision_conflict", "基线修订已过期，草稿已保留：" + message))
				: "compile_failed".equals(code)
						? Mono.error(new IntelligenceException(422, "hypit_compile_failed", message))
						: Mono.error(new IntelligenceException(503, "hypit_backend_unavailable", message));
	}

	@SuppressWarnings("unchecked")
	private Mono<ApplyResult> convergeApplied(UUID projectId, ChangesetRow row, SidecarCommand receipt) {
		Map<String, Object> result = HypitJson.mapValue(receipt.result());
		long revision = HypitJson.longValue(result.get("revision"), 0);
		String manifestHash = HypitJson.stringValue(result.get("manifestHash"), "");
		Object paths = result.get("appliedPaths");
		List<String> appliedPaths = paths instanceof List<?> list
				? list.stream().map(String::valueOf).toList()
				: List.of();
		return Mono
				.defer(() -> db
						.sql("UPDATE hypit_changeset SET state = 'applied', applied_revision"
								+ " = :revision, updated_at = now() WHERE id = CAST(:id AS uuid) AND state IN"
								+ " ('draft', 'conflict') RETURNING " + COLS)
						.bind("id", row.id().toString()).bind("revision", revision).map(HypitChangesetService::mapRow)
						.one()
						.then(revisions.insert(new HypitRevisionRepository.RevisionRow(UUID.randomUUID(), projectId,
								revision, row.baseRevision(), manifestHash,
								HypitJson.stringValue(result.get("snapshotDir"), "snapshot:" + revision),
								row.commandId(), row.applyMode(), null)))
						.then(projects.advanceRevision(projectId, revision, manifestHash))
						.then(Mono.just(new ApplyResult(revision, manifestHash, appliedPaths))))
				.as(transactions::transactional);
	}

	private Mono<Void> markConflict(ChangesetRow row) {
		return db
				.sql("UPDATE hypit_changeset SET state = 'conflict', updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid) AND state = 'draft'")
				.bind("id", row.id().toString()).fetch().rowsUpdated().then();
	}

	public Mono<ChangesetRow> findById(UUID id) {
		return db.sql("SELECT " + COLS + " FROM hypit_changeset WHERE id = CAST(:id AS uuid)").bind("id", id.toString())
				.map(HypitChangesetService::mapRow).one();
	}

	private Mono<ChangesetRow> findByCommand(UUID commandId) {
		return db.sql("SELECT " + COLS + " FROM hypit_changeset WHERE command_id = CAST(:command AS uuid)")
				.bind("command", commandId.toString()).map(HypitChangesetService::mapRow).one();
	}

	private static ChangesetRow mapRow(io.r2dbc.spi.Readable row) {
		String commandId = row.get("command_id", String.class);
		Long applied = row.get("applied_revision", Long.class);
		return new ChangesetRow(UUID.fromString(row.get("id", String.class)),
				UUID.fromString(row.get("project_id", String.class)),
				commandId == null ? null : UUID.fromString(commandId),
				row.get("base_revision", Long.class) == null ? 0L : row.get("base_revision", Long.class),
				row.get("apply_mode", String.class), row.get("change_manifest_handle", String.class),
				row.get("check_status", String.class), row.get("state", String.class), applied);
	}
}
