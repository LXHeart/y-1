package com.grassland.intelligence.digitalhuman;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 数字人域逐表生命周期 handler（任务书 #105B C105B-01；B05 接注销编排；G02 扩全资源聚合）。
 *
 * <p>
 * B01 责任：为本迁移新建的全部 dh 表提供<b>真实</b>的清理 handler 与活动守卫（registry 登记指向本类），
 * 避免新表「先裸奔、G 阶段才补」。语义（K05/K09）：
 * <ul>
 * <li>个人正文/易失表（event/transcript/preview/profile_revision/profile
 * 行）随注销删除，幂等；</li>
 * <li>经济与审计事实（dh_invocation、dh_operation
 * receipt、dh_admin_audit、dh_catalog）不删除—— 费用 unknown 不当空闲，B05 在注销编排中做脱敏与
 * verified 条件；</li>
 * <li>所有语句绑定 owner 参数；DELETE 不经触发器（V85 口径：DELETE 清理放行）。</li>
 * </ul>
 *
 * <p>
 * C105G-02：{@link #eraseAll} 聚合全资源（远端/本地派生句柄 worker 收口 → Redis buffer/preview
 * 清除 → B/C 内容行 + F 媒体行删除），{@link #verifyResidue(String, UUID)}
 * 逐类残留核对——unknown/failed 不计 0，远端未确认不给 complete。
 */
@Component
public class DigitalHumanLifecycleService {

	/** 个人正文表（注销即删）；顺序子先父后（revision 先于 profile，event/transcript 先于 session 引用检查）。 */
	private static final List<String> CONTENT_TABLES = List.of("dh_event", "dh_transcript", "dh_preview",
			"dh_profile_revision", "dh_profile");

	/** 全部 dh 表（计数守卫覆盖；含不删除的经济/审计表）。 */
	private static final List<String> ALL_TABLES = List.of("dh_profile", "dh_profile_revision", "dh_catalog",
			"dh_operation", "dh_session", "dh_turn", "dh_event", "dh_transcript", "dh_invocation", "dh_preview",
			"dh_admin_audit");

	private static final String OWNER_COLUMN = "owner_account_id";

	/** dh_asset_attachment 的个人可删范围：附属的个人素材仍属本人（K13.5；组织/他人资产附件保留）。 */
	static final String ATTACHMENT_PERSONAL = "EXISTS (SELECT 1 FROM content_asset ca"
			+ " WHERE ca.id = dh_asset_attachment.asset_id AND ca.owner_account_id = :owner"
			+ " AND ca.library_type = 'personal')";

	private final DatabaseClient db;

	private final DigitalHumanCleanupWorker cleanupWorker;

	public DigitalHumanLifecycleService(DatabaseClient db, DigitalHumanCleanupWorker cleanupWorker) {
		this.db = db;
		this.cleanupWorker = cleanupWorker;
	}

	/** 活动守卫输入：逐表行计数（无行=不出现键，不默认 0 掩盖读错）。 */
	public Mono<Map<String, Long>> countRows(String accountId) {
		Map<String, Long> counts = new LinkedHashMap<>();
		Mono<Void> chain = Mono.empty();
		for (String table : ALL_TABLES) {
			if (table.equals("dh_catalog") || table.equals("dh_admin_audit")) {
				continue; // 平台配置/管理审计无 owner 列
			}
			chain = chain.then(db.sql("SELECT count(*) AS n FROM " + table + " WHERE " + OWNER_COLUMN + " = :owner")
					.bind("owner", accountId).map(row -> row.get("n", Long.class)).one()
					.doOnNext(count -> counts.put(table, count)).then());
		}
		return chain.thenReturn(counts);
	}

	/**
	 * 逐表删除个人正文（幂等；返回各表实际删除行数）。 经济事实表不动；dh_session/dh_turn 行由 注销 prepare
	 * 的活动计数先保证非终态不存在，B05 再决定元数据保留策略。
	 */
	public Mono<Map<String, Long>> erasePersonalContent(String accountId) {
		Map<String, Long> deleted = new LinkedHashMap<>();
		Mono<Void> chain = Mono.empty();
		for (String table : CONTENT_TABLES) {
			chain = chain.then(
					db.sql("DELETE FROM " + table + " WHERE " + OWNER_COLUMN + " = :owner").bind("owner", accountId)
							.fetch().rowsUpdated().doOnNext(rows -> deleted.put(table, rows.longValue())).then());
		}
		return chain.thenReturn(deleted);
	}

	// ---------- B05：注销编排签名（countActive / eraseStatic / verifyResidue） ----------

	/** K07.1 ErasureProgress：counts 缺项不默认 0（读错即失败）；complete=本域可核对部分全部收口。 */
	public record ErasureProgress(UUID manifestId, Map<String, Long> counts, int remaining, int retained, int failed,
			boolean complete) {
	}

	/**
	 * 活动计数（TC105B-05-01/03）：非终态 session（含 cleanup_pending）、pending/running/unknown
	 * operation、未决 invocation——「暂停/ending/unknown 不当空闲」，注销 prepare 不得提前放行。
	 */
	public Mono<Map<String, Long>> countActive(String accountId) {
		Map<String, Long> counts = new LinkedHashMap<>();
		Mono<Void> chain = db
				.sql("SELECT count(*) AS n FROM dh_session WHERE owner_account_id = :owner"
						+ " AND (state NOT IN ('ended','failed') OR cleanup_pending)")
				.bind("owner", accountId).map(row -> row.get("n", Long.class)).one()
				.doOnNext(n -> putIfPositive(counts, "dh_session", n)).then()
				.then(db.sql("SELECT count(*) AS n FROM dh_operation WHERE owner_account_id = :owner"
						+ " AND state IN ('pending','running','unknown')").bind("owner", accountId)
						.map(row -> row.get("n", Long.class)).one()
						.doOnNext(n -> putIfPositive(counts, "dh_operation", n)).then())
				.then(db.sql("SELECT count(*) AS n FROM dh_invocation WHERE owner_account_id = :owner"
						+ " AND (state IN ('reserved','preparing','prepared','dispatched','unknown')"
						+ " OR settlement_state IN ('pending','failed'))").bind("owner", accountId)
						.map(row -> row.get("n", Long.class)).one()
						.doOnNext(n -> putIfPositive(counts, "dh_invocation", n)).then());
		return chain.thenReturn(counts);
	}

	private static void putIfPositive(Map<String, Long> counts, String kind, Long value) {
		if (value != null && value > 0) {
			counts.put(kind, value);
		}
	}

	/**
	 * 静态域清理（B 只开放 static 域；C/D 资源依据已建表零行运行）：只删该 owner 的
	 * profile/revision/transcript/turn/event/preview/operation 内容行；dh_invocation
	 * 经济事实 脱敏保留（未决调用计入 remaining，不伪 complete）。
	 */
	public Mono<ErasureProgress> eraseStatic(String accountId, UUID manifestId) {
		Map<String, Long> deleted = new LinkedHashMap<>();
		Mono<Void> chain = Mono.empty();
		for (String table : CONTENT_TABLES) {
			chain = chain
					.then(db.sql("DELETE FROM " + table + " WHERE " + OWNER_COLUMN + " = :owner")
							.bind("owner", accountId).fetch().rowsUpdated()
							.doOnNext(rows -> deleted.put(table, rows.longValue())).then())
					.then(db.sql("DELETE FROM dh_operation WHERE owner_account_id = :owner").bind("owner", accountId)
							.fetch().rowsUpdated()
							.doOnNext(rows -> deleted.merge("dh_operation", rows.longValue(), Long::sum)).then());
		}
		return chain.then(verifyResidue(accountId, manifestId, deleted));
	}

	/** C105C-04：注销路径的转写清理（删除墓碑口径——正文行删除，墓碑语义由 dh_session 行保留）。 */
	public Mono<Long> eraseTranscript(String accountId) {
		return db.sql("DELETE FROM dh_transcript WHERE owner_account_id = :owner").bind("owner", accountId).fetch()
				.rowsUpdated().map(rows -> rows == null ? 0L : rows.longValue()).defaultIfEmpty(0L);
	}

	/** 残留核对：经济事实（未决 invocation）计入 retained/remaining——内容已清但未决时不给 complete。 */
	public Mono<ErasureProgress> verifyResidue(String accountId, UUID manifestId, Map<String, Long> counts) {
		Mono<Long> unresolved = db
				.sql("SELECT count(*) AS n FROM dh_invocation WHERE owner_account_id = :owner"
						+ " AND (state = 'unknown' OR settlement_state IN ('pending','failed'))")
				.bind("owner", accountId).map(row -> row.get("n", Long.class)).one().defaultIfEmpty(0L);
		Mono<Long> residue = db
				.sql("SELECT count(*) AS n FROM dh_profile p LEFT JOIN dh_profile_revision r"
						+ " ON r.profile_id = p.id WHERE p.owner_account_id = :owner")
				.bind("owner", accountId).map(row -> row.get("n", Long.class)).one().defaultIfEmpty(0L);
		return Mono.zip(unresolved, residue).map(tuple -> {
			long unresolvedInvocations = tuple.getT1();
			long residueRows = tuple.getT2();
			return new ErasureProgress(manifestId, counts, (int) (unresolvedInvocations + residueRows),
					(int) unresolvedInvocations, residueRows > 0 ? 1 : 0,
					unresolvedInvocations == 0 && residueRows == 0);
		});
	}

	// ---------- C105G-02：全资源聚合清理与逐类残留核对 ----------

	/** 逐类残留报告：remaining=应删未删；retained=按 Scope 保留（组织资产附件/未决经济事实）。 */
	public record ResidueReport(Map<String, Long> remaining, Map<String, Long> retained, int failedCleanup,
			boolean clean) {
	}

	/**
	 * 全资源聚合清理（C105G-02 目标签名）：① 远端/本地派生句柄经 {@link DigitalHumanCleanupWorker}
	 * 收口（清理期不新增登记——未确认/失败保留登记行作证据，外部句柄未确认计 remaining）；② 会话内容缓冲 与预览易失键逐 key 删除（不只删
	 * Postgres）；③ B/C 内容行 + operation 删除；④ worker 干净且外部 句柄全确认时才删 F
	 * 媒体行（attachment/recording/cleanup/avatar——失败登记行不得随批次消失）； ⑤ 残留核对。幂等可重跑。
	 */
	public Mono<ErasureProgress> eraseAll(String accountId, UUID manifestId) {
		return cleanupWorker.advanceForErasure(accountId)
				.flatMap(advance -> cleanupWorker.eraseVolatileKeys(accountId).then(cleanupWorker.residue(accountId))
						.flatMap(workerResidue -> eraseContentRows(accountId)
								.then(workerResidue.clean() && advance.unconfirmedExternal() == 0
										? eraseMediaRows(accountId)
										: Mono.just(Map.<String, Long>of()))
								.flatMap(mediaDeleted -> verifyResidue(accountId, manifestId).map(report -> {
									Map<String, Long> counts = new LinkedHashMap<>();
									counts.putAll(mediaDeleted);
									int remaining = report.remaining().values().stream().mapToInt(Long::intValue).sum()
											+ (int) advance.unconfirmedExternal();
									int retained = report.retained().values().stream().mapToInt(Long::intValue).sum();
									boolean clean = report.clean() && advance.unconfirmedExternal() == 0;
									return new ErasureProgress(manifestId, counts, remaining, retained,
											report.failedCleanup(), clean);
								}))));
	}

	private Mono<Map<String, Long>> eraseContentRows(String accountId) {
		Map<String, Long> deleted = new LinkedHashMap<>();
		Mono<Void> chain = Mono.empty();
		for (String table : CONTENT_TABLES) {
			chain = chain.then(
					db.sql("DELETE FROM " + table + " WHERE " + OWNER_COLUMN + " = :owner").bind("owner", accountId)
							.fetch().rowsUpdated().doOnNext(rows -> deleted.put(table, rows.longValue())).then());
		}
		for (String table : List.of("dh_turn", "dh_session", "dh_operation")) {
			chain = chain
					.then(db.sql("DELETE FROM " + table + " WHERE owner_account_id = :owner").bind("owner", accountId)
							.fetch().rowsUpdated().doOnNext(rows -> deleted.put(table, rows.longValue())).then());
		}
		return chain.thenReturn(deleted);
	}

	/** F 媒体行删除（attachment 按个人素材 Scope；worker 未收口时由调用方跳过整段）。 */
	private Mono<Map<String, Long>> eraseMediaRows(String accountId) {
		Map<String, Long> deleted = new LinkedHashMap<>();
		Mono<Void> chain = db
				.sql("DELETE FROM dh_asset_attachment WHERE owner_account_id = :owner AND " + ATTACHMENT_PERSONAL)
				.bind("owner", accountId).fetch().rowsUpdated()
				.doOnNext(rows -> deleted.put("dh_asset_attachment", rows.longValue())).then()
				.then(db.sql("DELETE FROM dh_recording WHERE owner_account_id = :owner").bind("owner", accountId)
						.fetch().rowsUpdated().doOnNext(rows -> deleted.put("dh_recording", rows.longValue())).then())
				.then(db.sql("DELETE FROM dh_cleanup WHERE owner_account_id = :owner").bind("owner", accountId).fetch()
						.rowsUpdated().doOnNext(rows -> deleted.put("dh_cleanup", rows.longValue())).then())
				.then(db.sql("DELETE FROM dh_avatar WHERE owner_account_id = :owner").bind("owner", accountId).fetch()
						.rowsUpdated().doOnNext(rows -> deleted.put("dh_avatar", rows.longValue())).then());
		return chain.thenReturn(deleted);
	}

	/**
	 * 逐类残留核对（C105G-02 目标签名）：应删表按 owner 计数；attachment 只计个人素材范围（组织资产附件属
	 * 保留）；dh_cleanup 计非终态行；未决经济事实计入 retained——<b>unknown/failed 不计 0</b>。
	 */
	public Mono<ResidueReport> verifyResidue(String accountId, UUID manifestId) {
		return db
				.sql("""
						SELECT
						  (SELECT count(*) FROM dh_profile WHERE owner_account_id = :owner)::bigint AS dh_profile,
						  (SELECT count(*) FROM dh_profile_revision WHERE owner_account_id = :owner)::bigint AS dh_profile_revision,
						  (SELECT count(*) FROM dh_transcript WHERE owner_account_id = :owner)::bigint AS dh_transcript,
						  (SELECT count(*) FROM dh_turn WHERE owner_account_id = :owner)::bigint AS dh_turn,
						  (SELECT count(*) FROM dh_event WHERE owner_account_id = :owner)::bigint AS dh_event,
						  (SELECT count(*) FROM dh_session WHERE owner_account_id = :owner)::bigint AS dh_session,
						  (SELECT count(*) FROM dh_preview WHERE owner_account_id = :owner)::bigint AS dh_preview,
						  (SELECT count(*) FROM dh_operation WHERE owner_account_id = :owner)::bigint AS dh_operation,
						  (SELECT count(*) FROM dh_recording WHERE owner_account_id = :owner)::bigint AS dh_recording,
						  (SELECT count(*) FROM dh_avatar WHERE owner_account_id = :owner)::bigint AS dh_avatar,
						  (SELECT count(*) FROM dh_cleanup WHERE owner_account_id = :owner
						     AND state IN ('pending','deleting','failed'))::bigint AS dh_cleanup_open,
						  (SELECT count(*) FROM dh_cleanup WHERE owner_account_id = :owner
						     AND state = 'failed')::bigint AS dh_cleanup_failed,
						  (SELECT count(*) FROM dh_asset_attachment WHERE owner_account_id = :owner AND %s)::bigint AS dh_attachment_personal,
						  (SELECT count(*) FROM dh_asset_attachment WHERE owner_account_id = :owner AND NOT (%s))::bigint AS dh_attachment_retained,
						  (SELECT count(*) FROM dh_invocation WHERE owner_account_id = :owner
						     AND (state = 'unknown' OR settlement_state IN ('pending','failed')))::bigint AS dh_invocation_unresolved
						"""
						.formatted(ATTACHMENT_PERSONAL, ATTACHMENT_PERSONAL))
				.bind("owner", accountId).map(row -> {
					Map<String, Long> remaining = new LinkedHashMap<>();
					Map<String, Long> retained = new LinkedHashMap<>();
					for (String kind : List.of("dh_profile", "dh_profile_revision", "dh_transcript", "dh_turn",
							"dh_event", "dh_session", "dh_preview", "dh_operation", "dh_recording", "dh_avatar",
							"dh_cleanup_open", "dh_attachment_personal")) {
						Long value = row.get(kind, Long.class);
						if (value != null && value > 0) {
							remaining.put(kind, value);
						}
					}
					putPositive(retained, "dh_asset_attachment_shared_assets",
							row.get("dh_attachment_retained", Long.class));
					putPositive(retained, "dh_invocation_unresolved", row.get("dh_invocation_unresolved", Long.class));
					int failedCleanup = row.get("dh_cleanup_failed", Long.class) == null
							? 0
							: row.get("dh_cleanup_failed", Long.class).intValue();
					return new ResidueReport(remaining, retained, failedCleanup, remaining.isEmpty());
				}).one().defaultIfEmpty(new ResidueReport(Map.of(), Map.of(), 0, true));
	}

	private static void putPositive(Map<String, Long> target, String kind, Long value) {
		if (value != null && value > 0) {
			target.put(kind, value);
		}
	}
}
