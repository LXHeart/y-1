package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanCatalogService.ValidatedCombination;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationKind;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.Page;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.Profile;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.ProfileInput;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.ProfileRevisionRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.ProfileRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.ProfileStatus;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.Tone;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 角色档案（任务书 #105B C105B-03 / K02）：revision 冻结与 CAS、操作幂等同事务、keyset 游标。
 *
 * <p>
 * create/update/delete 与 dh_operation 同事务：同键同体重放回原结果（响应丢失可安全重试），异体 409
 * {@code dh_request_conflict}。update 是全表单 PATCH + expectedVersion：version 不符
 * 409 {@code dh_version_conflict}；成功插入 immutable revision（旧会话快照不受新版本影响）；失败不递增
 * version、不留孤儿 revision（同事务回滚）。删除仅无活动 session 引用时软删。列表 keyset 游标绑定 owner+filter
 * 哈希，伪造游标 422；游标值经严格格式校验（RFC3339/UUID）后才入 SQL，不拼用户原文。
 */
@Component
public class DigitalHumanProfileService {

	/** K02 字段限制（码点）：name 1～40、persona 1～4000、greeting 0～200、voiceId 1～128。 */
	private static final int NAME_MAX = 40;
	private static final int PERSONA_MAX = 4000;
	private static final int GREETING_MAX = 200;
	private static final int VOICE_ID_MAX = 128;
	static final int PAGE_DEFAULT = 20;
	static final int PAGE_MAX = 100;

	private final DatabaseClient db;
	private final TransactionalOperator transactions;
	private final DigitalHumanOperations operations;
	private final DigitalHumanCatalogService catalog;

	public DigitalHumanProfileService(DatabaseClient db, TransactionalOperator transactions,
			DigitalHumanOperations operations, DigitalHumanCatalogService catalog) {
		this.db = db;
		this.transactions = transactions;
		this.operations = operations;
		this.catalog = catalog;
	}

	// ---------- create ----------

	public record CreateResult(ProfileRow profile, ProfileRevisionRow revision, boolean createdNow) {
	}

	/** API03：新建（revision1）；同键同体 200 原 Profile（createdNow=false）。 */
	public Mono<CreateResult> create(PersonalActor actor, ProfileInput input, UUID requestId) {
		return catalog.validateCombination(input)
				.flatMap(validated -> transactions.transactional(
						operations.reserve(actor, OperationKind.profile_create, requestId, payloadHash(input), null)
								.flatMap(operation -> {
									if (operation.resourceId() != null) {
										// 幂等重放：原资源已落库（reserve 只在首插时 resourceId 为 null）。
										UUID profileId = UUID.fromString(operation.resourceId());
										return findRow(actor, profileId).flatMap(
												row -> findRevision(actor.accountId(), profileId, row.activeRevision())
														.map(rev -> new CreateResult(row, rev, false)));
									}
									return insert(actor, input, validated, operation);
								})));
	}

	private Mono<CreateResult> insert(PersonalActor actor, ProfileInput input, ValidatedCombination validated,
			OperationRow operation) {
		UUID profileId = UUID.randomUUID();
		return db.sql("""
				INSERT INTO dh_profile(id, owner_account_id, name, active_revision, status)
				VALUES (CAST(:id AS uuid), :owner, :name, 1, 'active')
				""").bind("id", profileId.toString()).bind("owner", actor.accountId()).bind("name", input.name()).then()
				.then(insertRevision(profileId, actor.accountId(), 1, input, validated))
				.then(operations.attachResource(UUID.fromString(operation.id()), profileId))
				.then(completeSucceeded(UUID.fromString(operation.id()), profileId))
				.then(findRow(actor, profileId).flatMap(row -> findRevision(actor.accountId(), profileId, 1)
						.map(rev -> new CreateResult(row, rev, true))));
	}

	// ---------- update ----------

	/**
	 * API05：全表单 PATCH + expectedVersion；成功插 revision(N+1) 并推进
	 * active_revision/version。
	 */
	public Mono<ProfileRow> update(PersonalActor actor, UUID profileId, ProfileInput input, int expectedVersion,
			UUID requestId) {
		Map<String, Object> hashFields = payloadFields(input);
		hashFields.put("expectedVersion", expectedVersion);
		return catalog.validateCombination(input)
				.flatMap(
						validated -> transactions.transactional(operations
								.reserve(actor, OperationKind.profile_update, requestId,
										DigitalHumanOperations.canonicalHash(hashFields), profileId)
								.flatMap(operation -> {
									if (operation.state() == OperationState.succeeded) {
										// 幂等重放：原更新已提交，回当前行。
										return requireActiveRow(actor, profileId);
									}
									return db.sql("""
											UPDATE dh_profile SET name = :name, version = version + 1,
											       active_revision = active_revision + 1, updated_at = now()
											WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner
											  AND status = 'active' AND version = :expected
											RETURNING active_revision
											""").bind("id", profileId.toString()).bind("owner", actor.accountId())
											.bind("name", input.name()).bind("expected", expectedVersion)
											.map(row -> row.get("active_revision", Integer.class)).one()
											.switchIfEmpty(Mono.error(new IntelligenceException(409,
													"dh_version_conflict", "角色已被其它修改更新，请刷新后重试。")))
											.flatMap(revision -> insertRevision(profileId, actor.accountId(), revision,
													input, validated)
													.then(completeSucceeded(UUID.fromString(operation.id()), profileId))
													.then(requireActiveRow(actor, profileId)));
								})));
	}

	// ---------- delete ----------

	public record DeletedResult(UUID id, boolean deletedNow) {
	}

	/** API06：软删；活动 session 引用 → 409 dh_state_conflict；同键重放幂等。 */
	public Mono<DeletedResult> delete(PersonalActor actor, UUID profileId, UUID requestId) {
		Mono<DeletedResult> body = operations
				.reserve(actor, OperationKind.profile_delete, requestId,
						DigitalHumanOperations.canonicalHash(Map.of("profileId", profileId.toString())), profileId)
				.flatMap(operation -> {
					if (operation.state() == OperationState.succeeded) {
						return Mono.just(new DeletedResult(profileId, false));
					}
					// 活动引用检查在事务内（锁内复查，不靠先查后写）。
					return db
							.sql("SELECT count(*) AS n FROM dh_session WHERE profile_id = CAST(:p AS uuid)"
									+ " AND state NOT IN ('ended','failed')")
							.bind("p", profileId.toString()).map(row -> row.get("n", Long.class)).one()
							.flatMap(active -> {
								if (active != null && active > 0) {
									return Mono.<DeletedResult>error(new IntelligenceException(409, "dh_state_conflict",
											"角色仍被进行中的会话使用，请先结束会话。"));
								}
								return db.sql("""
										UPDATE dh_profile SET status = 'deleted', deleted_at = now(),
										       version = version + 1, updated_at = now()
										WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner
										  AND status = 'active'
										RETURNING id::text
										""").bind("id", profileId.toString()).bind("owner", actor.accountId())
										.map(row -> row.get("id", String.class)).one()
										.map(id -> new DeletedResult(profileId, true))
										.defaultIfEmpty(new DeletedResult(profileId, false));
							}).flatMap(result -> completeSucceeded(UUID.fromString(operation.id()), profileId)
									.thenReturn(result));
				});
		return transactions.transactional(body);
	}

	private Mono<Void> completeSucceeded(UUID operationId, UUID resourceId) {
		return db
				.sql("UPDATE dh_operation SET state = 'succeeded', result_ref = CAST(:r AS uuid),"
						+ " version = version + 1, updated_at = now() WHERE id = CAST(:id AS uuid)")
				.bind("id", operationId.toString()).bind("r", resourceId.toString()).then();
	}

	// ---------- 读 ----------

	/** API04：非本人/已删/不存在统一 404。 */
	public Mono<Profile> get(PersonalActor actor, UUID profileId) {
		return requireActiveRow(actor, profileId).flatMap(
				row -> findRevision(actor.accountId(), profileId, row.activeRevision()).map(rev -> toDto(row, rev)));
	}

	/**
	 * API02：keyset 分页（updatedAt DESC, id DESC），limit 缺省 20 最大 100；活动 revision 联查。
	 */
	public Mono<Page<Profile>> list(PersonalActor actor, String cursor, Integer limit) {
		int pageSize = normalizeLimit(limit);
		String scope = scopeHash(actor.accountId());
		String positionClause = "";
		if (cursor != null && !cursor.isBlank()) {
			String[] position = decodeCursor(cursor, scope);
			positionClause = " AND ROW(p.updated_at, p.id) < ROW(CAST('" + position[0] + "' AS timestamptz), CAST('"
					+ position[1] + "' AS uuid))";
		}
		String sql = LIST_SELECT + " WHERE p.owner_account_id = :owner AND p.status = 'active'" + positionClause
				+ " ORDER BY p.updated_at DESC, p.id DESC LIMIT " + (pageSize + 1);
		return db.sql(sql).bind("owner", actor.accountId()).map(DigitalHumanProfileService::mapListRow).all()
				.collectList().map(rows -> {
					if (rows.size() <= pageSize) {
						return new Page<>(rows, null);
					}
					Profile last = rows.get(pageSize - 1);
					return new Page<>(new ArrayList<>(rows.subList(0, pageSize)),
							encodeCursor(last.updatedAt().toString(), last.id(), scope));
				});
	}

	private static final String LIST_SELECT = """
			SELECT p.id::text AS id, p.name, p.status, p.version, p.created_at, p.updated_at,
			       r.persona, r.greeting, r.tone, r.avatar_id::text AS avatar_id, r.voice_id, r.catalog_version
			FROM dh_profile p JOIN dh_profile_revision r
			  ON r.profile_id = p.id AND r.revision = p.active_revision
			""";

	private static Profile mapListRow(io.r2dbc.spi.Readable r) {
		return new Profile(r.get("id", String.class), r.get("name", String.class), r.get("persona", String.class),
				r.get("greeting", String.class), Tone.valueOf(r.get("tone", String.class)),
				r.get("avatar_id", String.class), r.get("voice_id", String.class),
				r.get("catalog_version", Integer.class), r.get("version", Integer.class),
				ProfileStatus.valueOf(r.get("status", String.class)),
				toInstant(r.get("created_at", OffsetDateTime.class)),
				toInstant(r.get("updated_at", OffsetDateTime.class)));
	}

	// ---------- 游标（绑定 owner 哈希；值经严格格式校验后才入 SQL，不拼用户原文） ----------

	private static String scopeHash(String owner) {
		return DigitalHumanOperations.canonicalHash(Map.of("owner", owner)).substring(0, 16);
	}

	private static String encodeCursor(String updatedAt, String id, String scope) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString((scope + "|" + updatedAt + "|" + id).getBytes(StandardCharsets.UTF_8));
	}

	private static String[] decodeCursor(String cursor, String expectedScope) {
		String raw;
		try {
			raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException malformed) {
			throw new IntelligenceException(422, "dh_invalid_input", "分页游标无效。");
		}
		String[] parts = raw.split("\\|", -1);
		if (parts.length != 3 || !parts[0].equals(expectedScope)) {
			throw new IntelligenceException(422, "dh_invalid_input", "分页游标无效。");
		}
		try {
			Instant.parse(parts[1]);
			UUID.fromString(parts[2]);
		} catch (Exception invalid) {
			throw new IntelligenceException(422, "dh_invalid_input", "分页游标无效。");
		}
		return new String[]{parts[1], parts[2]};
	}

	private static int normalizeLimit(Integer limit) {
		if (limit == null) {
			return PAGE_DEFAULT;
		}
		if (limit < 1 || limit > PAGE_MAX) {
			throw new IntelligenceException(422, "dh_invalid_input", "limit 必须在 1～100 之间。");
		}
		return limit;
	}

	// ---------- 字段校验（K02：先 trim、码点精确、U+0000 拒绝；DB 长度只是上界保护） ----------

	/** 校验并 trim 输入；不合法 → 422 dh_invalid_input。 */
	public static ProfileInput sanitize(ProfileInput input) {
		if (input == null) {
			throw invalid("请求体不能为空。");
		}
		String name = requireCodePoints(input.name(), "name", 1, NAME_MAX, true);
		String persona = requireCodePoints(input.persona(), "persona", 1, PERSONA_MAX, true);
		String greeting = requireCodePoints(input.greeting() == null ? "" : input.greeting(), "greeting", 0,
				GREETING_MAX, true);
		String voiceId = requireCodePoints(input.voiceId(), "voiceId", 1, VOICE_ID_MAX, false);
		if (input.tone() == null) {
			throw invalid("tone 必填。");
		}
		if (input.avatarId() == null || input.avatarId().isBlank()) {
			throw invalid("avatarId 必填。");
		}
		return new ProfileInput(name, persona, greeting, input.tone(), input.avatarId().trim(), voiceId,
				input.catalogVersion());
	}

	private static String requireCodePoints(String value, String field, int min, int max, boolean allowNewline) {
		String trimmed = value == null ? "" : value.strip();
		if (trimmed.indexOf('\0') >= 0) {
			throw invalid(field + " 不允许包含空字符。");
		}
		if (!allowNewline && trimmed.indexOf('\n') >= 0) {
			throw invalid(field + " 不允许换行。");
		}
		int codePoints = trimmed.codePointCount(0, trimmed.length());
		if (codePoints < min || codePoints > max) {
			throw invalid(field + " 长度须在 " + min + "～" + max + " 个字符之间。");
		}
		return trimmed;
	}

	private static IntelligenceException invalid(String message) {
		return new IntelligenceException(422, "dh_invalid_input", message);
	}

	// ---------- payloadHash 业务字段（K13.6：不含 requestId） ----------

	static Map<String, Object> payloadFields(ProfileInput input) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("name", input.name());
		fields.put("persona", input.persona());
		fields.put("greeting", input.greeting());
		fields.put("tone", input.tone().name());
		fields.put("avatarId", input.avatarId());
		fields.put("voiceId", input.voiceId());
		fields.put("catalogVersion", input.catalogVersion());
		return fields;
	}

	static String payloadHash(ProfileInput input) {
		return DigitalHumanOperations.canonicalHash(payloadFields(input));
	}

	// ---------- SQL 原语 ----------

	private static final String SELECT_PREFIX = "SELECT id::text, owner_account_id, name, active_revision, status,"
			+ " deleted_at, version, created_at, updated_at FROM dh_profile";

	private Mono<ProfileRow> requireActiveRow(PersonalActor actor, UUID profileId) {
		return findRow(actor, profileId).filter(row -> row.status() == ProfileStatus.active)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")));
	}

	private Mono<ProfileRow> findRow(PersonalActor actor, UUID profileId) {
		return db.sql(SELECT_PREFIX + " WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner")
				.bind("id", profileId.toString()).bind("owner", actor.accountId()).map(this::mapProfile).one();
	}

	private Mono<ProfileRevisionRow> insertRevision(UUID profileId, String owner, int revision, ProfileInput input,
			ValidatedCombination validated) {
		return db.sql("""
				INSERT INTO dh_profile_revision(id, owner_account_id, profile_id, revision, persona, greeting,
				    tone, avatar_id, avatar_revision, voice_id, catalog_version)
				VALUES (CAST(:rid AS uuid), :owner, CAST(:id AS uuid), :revision, :persona, :greeting, :tone,
				    CAST(:avatarId AS uuid), :avatarRevision, :voiceId, :catalogVersion)
				""").bind("rid", UUID.randomUUID().toString()).bind("owner", owner).bind("id", profileId.toString())
				.bind("revision", revision).bind("persona", input.persona()).bind("greeting", input.greeting())
				.bind("tone", input.tone().name()).bind("avatarId", input.avatarId())
				.bind("avatarRevision", validated.avatarRevision()).bind("voiceId", input.voiceId())
				.bind("catalogVersion", validated.catalogVersion()).then()
				.then(findRevision(owner, profileId, revision));
	}

	private Mono<ProfileRevisionRow> findRevision(String owner, UUID profileId, int revision) {
		return db.sql("""
				SELECT id::text, owner_account_id, profile_id::text, revision, persona, greeting, tone,
				       avatar_id::text, avatar_revision, voice_id, catalog_version, version, created_at, updated_at
				FROM dh_profile_revision WHERE profile_id = CAST(:id AS uuid) AND revision = :revision
				  AND owner_account_id = :owner
				""").bind("id", profileId.toString()).bind("revision", revision).bind("owner", owner)
				.map(DigitalHumanProfileService::mapRevision).one();
	}

	private ProfileRow mapProfile(io.r2dbc.spi.Readable r) {
		return new ProfileRow(r.get("id", String.class), r.get("owner_account_id", String.class),
				r.get("name", String.class), r.get("active_revision", Integer.class),
				ProfileStatus.valueOf(r.get("status", String.class)),
				toInstant(r.get("deleted_at", OffsetDateTime.class)), r.get("version", Integer.class),
				toInstant(r.get("created_at", OffsetDateTime.class)),
				toInstant(r.get("updated_at", OffsetDateTime.class)));
	}

	private static ProfileRevisionRow mapRevision(io.r2dbc.spi.Readable r) {
		return new ProfileRevisionRow(r.get("id", String.class), r.get("owner_account_id", String.class),
				r.get("profile_id", String.class), r.get("revision", Integer.class), r.get("persona", String.class),
				r.get("greeting", String.class), Tone.valueOf(r.get("tone", String.class)),
				r.get("avatar_id", String.class), r.get("avatar_revision", Integer.class),
				r.get("voice_id", String.class), r.get("catalog_version", Integer.class),
				r.get("version", Integer.class), toInstant(r.get("created_at", OffsetDateTime.class)),
				toInstant(r.get("updated_at", OffsetDateTime.class)));
	}

	private static Instant toInstant(OffsetDateTime time) {
		return time == null ? null : time.toInstant();
	}

	private Profile toDto(ProfileRow row, ProfileRevisionRow revision) {
		return new Profile(row.id(), row.name(), revision.persona(), revision.greeting(), revision.tone(),
				revision.avatarId(), revision.voiceId(), revision.catalogVersion(), row.version(), row.status(),
				row.createdAt(), row.updatedAt());
	}
}
