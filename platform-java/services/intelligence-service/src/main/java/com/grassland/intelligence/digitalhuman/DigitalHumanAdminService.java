package com.grassland.intelligence.digitalhuman;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.Session;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.UsageUnits;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 数字人治理面（任务书 #105G C105G-03 / K10 ADMIN01～06、DH-R15/R16/R18）。
 *
 * <p>
 * 全部入口先经 requireAdmin（controller 层），服务层只收 {@link AdminActor}。配置白名单字段按 K10 （不接
 * URL/secret/原始 JSON）；expectedVersion CAS + 审计先落意图。terminate 不建旁路 kill：落审计后 以会话
 * owner 身份走 {@link DigitalHumanSessionService#end}（正常 end 状态机 + reaper 兜底）。
 * reconcile 只核销 unknown：证据格式校验 → CAS 版本 → 原 run 冻结价表既有
 * settle/compensate（不收金额字段）。
 */
@Component
public class DigitalHumanAdminService {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** 治理动作（dh_admin_audit.action；与端点一一对应）。 */
	public static final String ACTION_CONFIG_UPDATE = "config_update";
	public static final String ACTION_SESSION_TERMINATE = "session_terminate";
	public static final String ACTION_INVOCATION_RECONCILE = "invocation_reconcile";

	/** 平台管理员（requireAdmin 已核；accountId 用于审计 actor）。 */
	public record AdminActor(String accountId) {
	}

	// ---------- K10 DTO ----------

	public record StateToggle(String id, boolean enabled) {
	}

	/** ADMIN01/02 响应：dh_catalog 投影（版本 + 全部白名单开关/容量/批准集）。 */
	public record AdminConfig(int version, boolean enabled, boolean newSessionsAllowed, boolean recordingEnabled,
			boolean customAvatarEnabled, int maxSessionsGlobal, int maxQueuedGlobal, List<String> allowedBackendIds,
			List<StateToggle> presetAvatarStates, List<StateToggle> voiceStates, String billingNoticeVersion) {
	}

	/** ADMIN02 请求：全部业务字段可选（缺省=保持现值）；expectedVersion/requestId/reason 必填。 */
	public record AdminConfigUpdate(Boolean enabled, Boolean newSessionsAllowed, Boolean recordingEnabled,
			Boolean customAvatarEnabled, Integer maxSessionsGlobal, Integer maxQueuedGlobal,
			List<String> allowedBackendIds, List<StateToggle> presetAvatarStates, List<StateToggle> voiceStates,
			String billingNoticeVersion, int expectedVersion, UUID requestId, String reason) {
	}

	public record PhaseMetricView(String phase, Long lastDurationMs, Long p50Ms, Long p95Ms, int sampleCount) {
	}

	/** ADMIN03 行：元数据视图（无正文/无转写内容；计数与状态来自真实行）。 */
	public record AdminSessionView(String id, String profileId, String profileNameAtCreation, String state,
			String createdAt, String endedAt, boolean hasSavedTranscript, long recordingCount, long savedAssetCount,
			DigitalHumanRecords.SessionBilling billing, String workerId, String errorCode, boolean cleanupPending,
			List<PhaseMetricView> phaseMetrics) {
	}

	public record AdminSessionPage(List<AdminSessionView> items, String nextCursor) {
	}

	/** ADMIN05/06 行：经济事实摘要（confirmedCents 取原 run 实际消耗，治理面不自填金额）。 */
	public record InvocationSummaryView(String id, String sessionId, String stage, String state, String settlementState,
			int version, String providerModelLabel, String createdAt, String deadlineAt, UsageUnits usage,
			Long confirmedCents, String errorCode) {
	}

	public record InvocationSummaryPage(List<InvocationSummaryView> items, String nextCursor) {
	}

	/**
	 * ADMIN06 请求：outcome 必填；succeeded 须证据+confirmedUsage 实量齐全；failed 须
	 * confirmedUsage=null。
	 */
	public record ReconcileInput(UUID requestId, int expectedVersion, String outcome, String providerEvidenceRef,
			UsageUnits confirmedUsage, String reason) {
	}

	private final DatabaseClient db;
	private final DigitalHumanSessionService sessions;
	private final DigitalHumanInvocationService invocations;
	private final DigitalHumanInvocationRepository invoRepo;
	private final DigitalHumanAdminAuditRepository audit;
	private final TransactionalOperator transactions;

	public DigitalHumanAdminService(DatabaseClient db, DigitalHumanSessionService sessions,
			DigitalHumanInvocationService invocations, DigitalHumanInvocationRepository invoRepo,
			DigitalHumanAdminAuditRepository audit, TransactionalOperator transactions) {
		this.db = db;
		this.sessions = sessions;
		this.invocations = invocations;
		this.invoRepo = invoRepo;
		this.audit = audit;
		this.transactions = transactions;
	}

	// ---------- ADMIN01：配置读取（行未初始化 → 未配置投影 version=0，同目录面语义） ----------

	public Mono<AdminConfig> config() {
		return loadCatalogRow()
				.map(row -> row.config() == null ? unconfigured() : toAdminConfig(row.version(), row.config()));
	}

	private static AdminConfig unconfigured() {
		return new AdminConfig(0, false, false, false, false, 1, 10, List.of(), List.of(), List.of(), "dh-billing-v1");
	}

	// ---------- ADMIN02：配置更新（白名单 + CAS + 审计先落；expectedVersion=0 引导建行） ----------

	public Mono<AdminConfig> updateConfig(AdminActor actor, AdminConfigUpdate input) {
		return Mono.defer(() -> {
			validateUpdateInput(input);
			return transactions.transactional(loadCatalogRow().flatMap(current -> {
				if (current.version() != input.expectedVersion()) {
					return Mono.error(versionConflict(current.version()));
				}
				// 批准集只能引用控制面已启用且有凭据的 render 行（不存在/无凭据 → 422，先于 CAS）。
				// 审计意图与写入同事务：重放同 requestId 幂等（UNIQUE），CAS 失败不落新版本。
				return backendsExist(input.allowedBackendIds())
						.then(audit.append(actor.accountId(), ACTION_CONFIG_UPDATE, null, input.requestId(),
								input.reason().trim(),
								Map.of("expectedVersion", input.expectedVersion(), "newVersion",
										current.version() + 1)))
						.then(current.version() == 0 ? insertCatalog(actor, input) : casUpdate(actor, current, input));
			}));
		});
	}

	private Mono<AdminConfig> insertCatalog(AdminActor actor, AdminConfigUpdate input) {
		return db
				.sql("INSERT INTO dh_catalog(singleton_id, version, config_json, updated_by)"
						+ " VALUES (1, 1, CAST(:config AS jsonb), :actor) ON CONFLICT (singleton_id) DO NOTHING"
						+ " RETURNING version, config_json::text AS config")
				.bind("config", mergeConfig("{}", input)).bind("actor", actor.accountId())
				.map(row -> toAdminConfig(row.get("version", Integer.class), row.get("config", String.class))).one()
				.switchIfEmpty(Mono.error(versionConflict(1)));
	}

	private Mono<AdminConfig> casUpdate(AdminActor actor, CatalogRow current, AdminConfigUpdate input) {
		return db
				.sql("UPDATE dh_catalog SET config_json = CAST(:config AS jsonb), version = version + 1,"
						+ " updated_by = :actor, updated_at = now() WHERE singleton_id = 1 AND version = :expected"
						+ " RETURNING version, config_json::text AS config")
				.bind("config", mergeConfig(current.config(), input)).bind("actor", actor.accountId())
				.bind("expected", input.expectedVersion())
				.map(row -> toAdminConfig(row.get("version", Integer.class), row.get("config", String.class))).one()
				.switchIfEmpty(Mono.error(versionConflict(current.version())));
	}

	private static IntelligenceException versionConflict(int currentVersion) {
		return new IntelligenceException(409, "dh_version_conflict", "配置版本已变化，当前版本：" + currentVersion + "。");
	}

	private static void validateUpdateInput(AdminConfigUpdate input) {
		if (input.requestId() == null) {
			throw new IntelligenceException(422, "dh_invalid_input", "requestId 必填。");
		}
		if (input.reason() == null || input.reason().isBlank() || input.reason().trim().length() > 200) {
			throw new IntelligenceException(422, "dh_invalid_input", "reason 必填且不超过 200 字。");
		}
		if (input.expectedVersion() < 0) {
			throw new IntelligenceException(422, "dh_invalid_input", "expectedVersion 不得为负。");
		}
		if (input.maxSessionsGlobal() != null && (input.maxSessionsGlobal() < 1 || input.maxSessionsGlobal() > 100)) {
			throw new IntelligenceException(422, "dh_invalid_input", "maxSessionsGlobal 须在 1～100。");
		}
		if (input.maxQueuedGlobal() != null && (input.maxQueuedGlobal() < 0 || input.maxQueuedGlobal() > 100)) {
			throw new IntelligenceException(422, "dh_invalid_input", "maxQueuedGlobal 须在 0～100。");
		}
		if (input.allowedBackendIds() != null) {
			Set<String> seen = new HashSet<>();
			for (String id : input.allowedBackendIds()) {
				if (id == null || id.isBlank() || !seen.add(id)) {
					throw new IntelligenceException(422, "dh_invalid_input", "allowedBackendIds 项须非空且不重复。");
				}
			}
		}
		validateToggles("presetAvatarStates", input.presetAvatarStates());
		validateToggles("voiceStates", input.voiceStates());
		if (input.billingNoticeVersion() != null && input.billingNoticeVersion().isBlank()) {
			throw new IntelligenceException(422, "dh_invalid_input", "billingNoticeVersion 不能为空白。");
		}
	}

	private static void validateToggles(String field, List<StateToggle> toggles) {
		if (toggles == null) {
			return;
		}
		for (StateToggle toggle : toggles) {
			if (toggle == null || toggle.id() == null || toggle.id().isBlank()) {
				throw new IntelligenceException(422, "dh_invalid_input", field + " 项须含非空 id。");
			}
		}
	}

	private Mono<Void> backendsExist(List<String> allowedBackendIds) {
		if (allowedBackendIds == null || allowedBackendIds.isEmpty()) {
			return Mono.empty();
		}
		return db
				.sql("SELECT id::text FROM platform_model_config WHERE capability = 'digital_human_render'"
						+ " AND enabled = true AND credential_id IS NOT NULL")
				.map(row -> row.get("id", String.class)).all().collectList()
				.flatMap(existing -> existing.containsAll(allowedBackendIds)
						? Mono.empty()
						: Mono.error(new IntelligenceException(422, "dh_invalid_input",
								"allowedBackendIds 含未启用或未配置凭据的后端。")));
	}

	private String mergeConfig(String currentJson, AdminConfigUpdate input) {
		try {
			ObjectNode config = (ObjectNode) JSON.readTree(currentJson == null ? "{}" : currentJson);
			if (input.enabled() != null) {
				config.put("enabled", input.enabled());
			}
			if (input.newSessionsAllowed() != null) {
				config.put("newSessionsAllowed", input.newSessionsAllowed());
			}
			if (input.recordingEnabled() != null) {
				config.put("recordingEnabled", input.recordingEnabled());
			}
			if (input.customAvatarEnabled() != null) {
				config.put("customAvatarEnabled", input.customAvatarEnabled());
			}
			if (input.maxSessionsGlobal() != null) {
				config.put("maxSessionsGlobal", input.maxSessionsGlobal());
			}
			if (input.maxQueuedGlobal() != null) {
				config.put("maxQueuedGlobal", input.maxQueuedGlobal());
			}
			if (input.allowedBackendIds() != null) {
				config.set("allowedBackendIds", JSON.valueToTree(input.allowedBackendIds()));
			}
			if (input.presetAvatarStates() != null) {
				config.set("presetAvatarStates", JSON.valueToTree(input.presetAvatarStates()));
			}
			if (input.voiceStates() != null) {
				config.set("voiceStates", JSON.valueToTree(input.voiceStates()));
			}
			if (input.billingNoticeVersion() != null) {
				config.put("billingNoticeVersion", input.billingNoticeVersion());
			}
			return JSON.writeValueAsString(config);
		} catch (Exception failure) {
			throw new IntelligenceException(502, "dh_runtime_unavailable", "目录配置不可读。");
		}
	}

	private AdminConfig toAdminConfig(int version, String configJson) {
		try {
			JsonNode config = JSON.readTree(configJson == null ? "{}" : configJson);
			return new AdminConfig(version, config.path("enabled").asBoolean(false),
					config.path("newSessionsAllowed").asBoolean(false),
					config.path("recordingEnabled").asBoolean(false),
					config.path("customAvatarEnabled").asBoolean(false), config.path("maxSessionsGlobal").asInt(1),
					config.path("maxQueuedGlobal").asInt(10), readStrings(config.path("allowedBackendIds")),
					readToggles(config.path("presetAvatarStates")), readToggles(config.path("voiceStates")),
					config.path("billingNoticeVersion").asText("dh-billing-v1"));
		} catch (Exception failure) {
			throw new IntelligenceException(502, "dh_runtime_unavailable", "目录配置不可读。");
		}
	}

	private static List<String> readStrings(JsonNode node) {
		List<String> values = new ArrayList<>();
		if (node != null && node.isArray()) {
			for (JsonNode item : node) {
				values.add(item.asText());
			}
		}
		return List.copyOf(values);
	}

	private static List<StateToggle> readToggles(JsonNode node) {
		List<StateToggle> values = new ArrayList<>();
		if (node != null && node.isArray()) {
			for (JsonNode item : node) {
				values.add(new StateToggle(item.path("id").asText(), item.path("enabled").asBoolean(true)));
			}
		}
		return List.copyOf(values);
	}

	private record CatalogRow(int version, String config) {
	}

	/** 行缺失 → (0,null)：未配置投影 / 引导建行判定共用。 */
	private Mono<CatalogRow> loadCatalogRow() {
		return db.sql("SELECT version, config_json::text AS config FROM dh_catalog WHERE singleton_id = 1")
				.map((row, meta) -> new CatalogRow(
						row.get("version", Integer.class) == null ? 0 : row.get("version", Integer.class),
						row.get("config", String.class)))
				.one().defaultIfEmpty(new CatalogRow(0, null));
	}

	// ---------- ADMIN03：会话元数据分页（K10 SessionListQuery：createdAt DESC,id
	// DESC；[from,to) ≤90 天） ----------

	public Mono<AdminSessionPage> sessions(String cursor, int limit, String profileId, String state, String from,
			String to) {
		int pageSize = Math.min(Math.max(limit, 1), 100);
		OffsetDateTime fromTs = parseRange(from, "from");
		OffsetDateTime toTs = parseRange(to, "to");
		if (fromTs != null && toTs != null && fromTs.plusDays(90).isBefore(toTs)) {
			throw new IntelligenceException(422, "dh_invalid_input", "时间跨度不得超过 90 天。");
		}
		String[] key = parseCursor(cursor);
		// bind() 拒 null：可空过滤/游标参数一律 bindNullable（null 走 bindNull）。
		DatabaseClient.GenericExecuteSpec spec = db.sql("""
				SELECT s.id::text, s.profile_id::text, s.profile_name_at_creation, s.state, s.created_at,
				       s.ended_at, s.save_transcript, s.transcript_version, s.content_deleted, s.worker_id,
				       s.error_code, s.cleanup_pending,
				       (SELECT count(*) FROM dh_recording r WHERE r.session_id = s.id)::bigint AS recordings,
				       (SELECT count(*) FROM dh_recording r WHERE r.session_id = s.id
				          AND r.state = 'saved' AND r.asset_id IS NOT NULL)::bigint AS saved_assets
				  FROM dh_session s
				 WHERE (:profileId IS NULL OR s.profile_id = CAST(:profileId AS uuid))
				   AND (:state IS NULL OR s.state = :state)
				   AND (:fromTs IS NULL OR s.created_at >= CAST(:fromTs AS timestamptz))
				   AND (:toTs IS NULL OR s.created_at < CAST(:toTs AS timestamptz))
				   AND (:cAt IS NULL OR s.created_at < CAST(:cAt AS timestamptz)
				        OR (s.created_at = CAST(:cAt AS timestamptz) AND s.id < CAST(:cId AS uuid)))
				 ORDER BY s.created_at DESC, s.id DESC LIMIT :n
				""");
		spec = bindNullable(spec, "profileId", profileId == null || profileId.isBlank() ? null : profileId);
		spec = bindNullable(spec, "state", state == null || state.isBlank() ? null : state);
		spec = bindNullable(spec, "fromTs", fromTs == null ? null : fromTs.toString());
		spec = bindNullable(spec, "toTs", toTs == null ? null : toTs.toString());
		spec = bindNullable(spec, "cAt", key[0]);
		spec = bindNullable(spec, "cId", key[1]);
		return spec.bind("n", pageSize + 1).map((row, meta) -> row).all().collectList().map(rows -> {
			PageSlice<AdminSessionView> slice = pageOf(rows, pageSize,
					row -> new AdminSessionView(row.get("id", String.class), row.get("profile_id", String.class),
							row.get("profile_name_at_creation", String.class), row.get("state", String.class),
							instant(row.get("created_at", OffsetDateTime.class)),
							instant(row.get("ended_at", OffsetDateTime.class)),
							Boolean.TRUE.equals(row.get("save_transcript", Boolean.class))
									&& row.get("transcript_version", Integer.class) != null
									&& row.get("transcript_version", Integer.class) > 0
									&& !Boolean.TRUE.equals(row.get("content_deleted", Boolean.class)),
							longOf(row.get("recordings")), longOf(row.get("saved_assets")),
							new DigitalHumanRecords.SessionBilling(0, 0, 0, 0, "pt"),
							blankToNull(row.get("worker_id", String.class)),
							blankToNull(row.get("error_code", String.class)),
							Boolean.TRUE.equals(row.get("cleanup_pending", Boolean.class)), List.of()),
					view -> view.createdAt() + "|" + view.id());
			return new AdminSessionPage(slice.items(), slice.nextCursor());
		});
	}

	/** 游标格式 createdAt|id；空/缺省返回 null 数组（无过滤）。 */
	private static String[] parseCursor(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return new String[]{null, null};
		}
		int split = cursor.lastIndexOf('|');
		if (split <= 0 || split == cursor.length() - 1) {
			throw new IntelligenceException(422, "dh_invalid_input", "游标格式非法。");
		}
		String createdAt = cursor.substring(0, split);
		String id = cursor.substring(split + 1);
		try {
			OffsetDateTime.parse(createdAt);
			UUID.fromString(id);
		} catch (Exception failure) {
			throw new IntelligenceException(422, "dh_invalid_input", "游标格式非法。");
		}
		return new String[]{createdAt, id};
	}

	private static OffsetDateTime parseRange(String value, String field) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return OffsetDateTime.parse(value);
		} catch (Exception failure) {
			throw new IntelligenceException(422, "dh_invalid_input", field + " 时间格式非法。");
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	// ---------- ADMIN04：终止（审计先落 + 正常 end 状态机，无旁路 kill） ----------

	public Mono<Session> terminate(AdminActor actor, UUID sessionId, UUID requestId, String reason) {
		return Mono.defer(() -> {
			if (requestId == null) {
				throw new IntelligenceException(422, "dh_invalid_input", "requestId 必填。");
			}
			if (reason == null || reason.isBlank() || reason.trim().length() > 200) {
				throw new IntelligenceException(422, "dh_invalid_input", "reason 必填且不超过 200 字。");
			}
			String trimmed = reason.trim();
			// 意图先落（404/失败也留证据；同 requestId 重放幂等）；随后以 owner 身份走正常 end。
			return audit.append(actor.accountId(), ACTION_SESSION_TERMINATE, sessionId, requestId, trimmed, Map.of())
					.then(db.sql("SELECT owner_account_id FROM dh_session WHERE id = CAST(:id AS uuid)")
							.bind("id", sessionId.toString()).map(row -> row.get("owner_account_id", String.class))
							.one())
					.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "会话不存在。")))
					.flatMap(owner -> sessions.end(new PersonalActor(owner), sessionId, requestId, "admin:" + trimmed)
							.then(sessions.get(new PersonalActor(owner), sessionId))
							.map(snapshot -> snapshot.session()));
		});
	}

	// ---------- ADMIN05：核对队列分页（K10 note：固定 state=unknown / settlement 未收口）
	// ----------

	public Mono<InvocationSummaryPage> invocations(String cursor, int limit) {
		int pageSize = Math.min(Math.max(limit, 1), 100);
		return bindNullable(db.sql("""
				SELECT inv.id::text, inv.session_id::text, inv.stage, inv.state, inv.settlement_state, inv.version,
				       inv.provider_snapshot::text AS provider_snapshot, inv.created_at, inv.deadline_at,
				       inv.usage_json::text AS usage_json, run.actual_cents
				  FROM dh_invocation inv
				  LEFT JOIN ai_run run ON run.id = inv.ai_run_id
				 WHERE (inv.state = 'unknown' OR (inv.state = 'succeeded'
				          AND inv.settlement_state IN ('pending', 'failed')))
				   AND (:cursor IS NULL OR inv.id::text > :cursor)
				 ORDER BY inv.id LIMIT :n
				"""), "cursor", cursor == null || cursor.isBlank() ? null : cursor).bind("n", pageSize + 1)
				.map((row, meta) -> row).all().collectList().map(rows -> {
					PageSlice<InvocationSummaryView> slice = pageOf(rows, pageSize, DigitalHumanAdminService::toSummary,
							view -> view.id());
					return new InvocationSummaryPage(slice.items(), slice.nextCursor());
				});
	}

	// ---------- ADMIN06：unknown 核对（原经济键 settle/compensate，不收金额） ----------

	public Mono<InvocationSummaryView> reconcile(AdminActor actor, UUID invocationId, ReconcileInput input) {
		return Mono.defer(() -> {
			validateReconcileInput(input);
			// 意图先落（独立于核对事务，失败/冲突也留证据）；同 requestId 重放幂等（UNIQUE）。
			Map<String, Object> metadata = new java.util.HashMap<>();
			metadata.put("outcome", input.outcome());
			metadata.put("providerEvidenceRef", String.valueOf(input.providerEvidenceRef()));
			Mono<Void> intent = audit.append(actor.accountId(), ACTION_INVOCATION_RECONCILE, invocationId,
					input.requestId(), input.reason().trim(), metadata).then();
			// 不套外层事务：settleSuccess/fail 自带事务（拥有提交权——外层 joined 事务的异步提交会与
			// 回读竞态，worker 同款无外层路径）；前置版本/状态读为 advisory，结算本身按经济键幂等。
			Mono<Void> proceed = invoRepo.findById(invocationId)
					.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "调用不存在。")))
					.flatMap(row -> {
						if (row.version() != input.expectedVersion()) {
							return Mono.error(new IntelligenceException(409, "dh_version_conflict",
									"调用版本已变化，当前版本：" + row.version() + "。"));
						}
						if (row.state() != DigitalHumanRecords.InvocationState.unknown) {
							return Mono.error(new IntelligenceException(409, "dh_state_conflict",
									"仅 unknown 调用可核对，当前：" + row.state().name()));
						}
						return Mono.just(row);
					}).flatMap(row -> invocations.rehydrate(invocationId).flatMap(prepared -> {
						// unknown→dispatched 先行 CAS：既有 markTerminal 白名单只认
						// reserved～dispatched（worker 从不结算 unknown；本端点即 K08「人工证据
						// 确认」入口）。中途崩溃停留在 dispatched，deadline 已过 → worker 复判
						// unknown 可重对。
						Mono<Void> dispatch = casDispatchFromUnknown(invocationId);
						return "succeeded".equals(input.outcome())
								? dispatch.then(invocations
										.settleSuccess(invocationId, prepared.context(),
												withConfirmedQuality(input.confirmedUsage()))
										.flatMap(ok -> Boolean.TRUE.equals(ok)
												? Mono.<Void>empty()
												: Mono.<Void>error(new IntelligenceException(502,
														"dh_runtime_unavailable", "结算失败，可重试。"))))
								: dispatch.then(invocations.fail(invocationId, prepared.context(),
										"admin_reconcile:" + input.providerEvidenceRef()).then());
					}));
			// 结算自有事务提交后回读摘要（含原 run 实际消耗）。
			return intent.then(proceed).then(invocationById(invocationId));
		});
	}

	/** unknown→dispatched 单向 CAS（仅本核对入口使用；并发重复核对第二个落空 409）。 */
	private Mono<Void> casDispatchFromUnknown(UUID invocationId) {
		return db
				.sql("UPDATE dh_invocation SET state = 'dispatched', version = version + 1, updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid) AND state = 'unknown' RETURNING id::text")
				.bind("id", invocationId.toString()).map(row -> row.get(0, String.class)).one()
				.switchIfEmpty(Mono.error(new IntelligenceException(409, "dh_state_conflict", "调用已被其他核对收口，请刷新。")))
				.then();
	}

	private Mono<InvocationSummaryView> invocationById(UUID invocationId) {
		return db.sql("""
				SELECT inv.id::text, inv.session_id::text, inv.stage, inv.state, inv.settlement_state, inv.version,
				       inv.provider_snapshot::text AS provider_snapshot, inv.created_at, inv.deadline_at,
				       inv.usage_json::text AS usage_json, run.actual_cents
				  FROM dh_invocation inv
				  LEFT JOIN ai_run run ON run.id = inv.ai_run_id
				 WHERE inv.id = CAST(:id AS uuid)
				""").bind("id", invocationId.toString()).map((row, meta) -> row).one()
				.map(DigitalHumanAdminService::toSummary);
	}

	private static UsageUnits withConfirmedQuality(UsageUnits usage) {
		return new UsageUnits(usage.inputTokens(), usage.outputTokens(), usage.audioInputMs(), usage.audioOutputMs(),
				usage.textCodePoints(), usage.renderMs(), usage.providerRequestId(), "confirmed");
	}

	private static void validateReconcileInput(ReconcileInput input) {
		if (input.requestId() == null) {
			throw new IntelligenceException(422, "dh_invalid_input", "requestId 必填。");
		}
		if (input.reason() == null || input.reason().isBlank() || input.reason().trim().length() > 200) {
			throw new IntelligenceException(422, "dh_invalid_input", "reason 必填且不超过 200 字。");
		}
		if (input.expectedVersion() < 1) {
			throw new IntelligenceException(422, "dh_invalid_input", "expectedVersion 必须大于 0。");
		}
		if (!List.of("succeeded", "failed").contains(input.outcome())) {
			throw new IntelligenceException(422, "dh_invalid_input", "outcome 只允许 succeeded/failed。");
		}
		if (input.providerEvidenceRef() != null
				&& (input.providerEvidenceRef().isBlank() || input.providerEvidenceRef().length() > 128)) {
			throw new IntelligenceException(422, "dh_invalid_input", "providerEvidenceRef 须 1～128 字。");
		}
		if ("succeeded".equals(input.outcome())) {
			if (input.providerEvidenceRef() == null) {
				throw new IntelligenceException(422, "dh_invalid_input", "succeeded 须提交 providerEvidenceRef。");
			}
			if (input.confirmedUsage() == null) {
				throw new IntelligenceException(422, "dh_invalid_input", "succeeded 须提交 confirmedUsage 实量。");
			}
			requireConfirmedUnits(input.confirmedUsage());
		} else if (input.confirmedUsage() != null) {
			throw new IntelligenceException(422, "dh_invalid_input", "failed 不得提交用量（补偿不核销）。");
		}
	}

	/** succeeded 证据实量齐全（未知 null 不当 0）：至少一项计量非空且全部非负。 */
	private static void requireConfirmedUnits(UsageUnits usage) {
		// Arrays.asList 容 null（K08：未知计量是 null 不是 0；List.of 会 NPE）。
		List<Long> units = java.util.Arrays.asList(usage.inputTokens(), usage.outputTokens(), usage.audioInputMs(),
				usage.audioOutputMs(), usage.renderMs());
		if (units.stream().noneMatch(Objects::nonNull)) {
			throw new IntelligenceException(422, "dh_invalid_input", "confirmedUsage 至少含一项实量计量。");
		}
		if (units.stream().anyMatch(value -> value != null && value < 0)) {
			throw new IntelligenceException(422, "dh_invalid_input", "计量不得为负。");
		}
	}

	// ---------- 分页与映射 ----------

	private record PageSlice<T>(List<T> items, String nextCursor) {
	}

	private static <T> PageSlice<T> pageOf(List<? extends io.r2dbc.spi.Readable> rows, int pageSize,
			java.util.function.Function<io.r2dbc.spi.Readable, T> mapper,
			java.util.function.Function<T, String> cursorOf) {
		boolean more = rows.size() > pageSize;
		List<T> items = new ArrayList<>();
		for (io.r2dbc.spi.Readable row : rows.subList(0, Math.min(rows.size(), pageSize))) {
			items.add(mapper.apply(row));
		}
		return new PageSlice<>(List.copyOf(items),
				more && !items.isEmpty() ? cursorOf.apply(items.get(items.size() - 1)) : null);
	}

	private static String instant(OffsetDateTime value) {
		return value == null ? null : value.toInstant().toString();
	}

	/** bind() 拒 null 值：可空参数统一走本入口（null → bindNull(text)）。 */
	private static DatabaseClient.GenericExecuteSpec bindNullable(DatabaseClient.GenericExecuteSpec spec, String name,
			String value) {
		return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
	}

	private static long longOf(Object value) {
		return value == null ? 0L : ((Number) value).longValue();
	}

	/** 行→摘要映射（ADMIN05/06 共用；金额/模型标签取自持久事实，缺失如实为 null/unknown）。 */
	static InvocationSummaryView toSummary(io.r2dbc.spi.Readable row) {
		return new InvocationSummaryView(row.get("id", String.class), row.get("session_id", String.class),
				row.get("stage", String.class), row.get("state", String.class),
				row.get("settlement_state", String.class),
				row.get("version", Integer.class) == null ? 0 : row.get("version", Integer.class),
				modelLabel(row.get("provider_snapshot", String.class)),
				instant(row.get("created_at", OffsetDateTime.class)),
				instant(row.get("deadline_at", OffsetDateTime.class)), usage(row.get("usage_json", String.class)),
				// ai_run.actual_cents 是 integer 列：按 Number 读再放宽数值（r2dbc 定型 Long 取 integer 会拒）。
				row.get("actual_cents") == null ? null : ((Number) row.get("actual_cents")).longValue(), null);
	}

	private static UsageUnits usage(String usageJson) {
		if (usageJson == null || usageJson.isBlank()) {
			return null;
		}
		try {
			return JSON.readValue(usageJson, UsageUnits.class);
		} catch (Exception failure) {
			return null; // 摘要视图不因 usage 腐败阻塞；结算核对走 ADMIN06 证据路径
		}
	}

	private static String modelLabel(String providerSnapshot) {
		try {
			JsonNode snapshot = JSON.readTree(providerSnapshot == null ? "{}" : providerSnapshot);
			String model = snapshot.path("model").asText(null);
			String provider = snapshot.path("provider").asText(null);
			if (model != null && provider != null) {
				return provider + "/" + model;
			}
			return model == null ? "unknown" : model;
		} catch (Exception failure) {
			return "unknown";
		}
	}
}
