package com.grassland.intelligence.digitalhuman;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationStage;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationKind;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.SessionState;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 连接/执行短期资格（任务书 #105D C105D-02 / 共享契约 K05、K07、K13.1）。
 *
 * <p>
 * 票据 = 32 随机字节 base64url，<b>原文只在签发响应出现一次</b>；Redis 只存 {@code dh:grant:{sha256}}
 * 的无密钥载荷（owner/session/leaseEpoch/channel/Origin/expiry 或
 * invocation/stage/inputHash），TTL 30 秒，核销用原子 GETDEL——同 grant 第二次核销必失败，失败错误不回
 * owner。grant 不是业务幂等键：核销（Redis）与 audio turn 创建（DB）不做假原子，DB 幂等由
 * {@code UNIQUE(session_id,request_id)} + turn_create operation 保证——票据被消费但 DB
 * 失败时，用户持 <b>新票据</b>与原 requestId 重放即续原 turn，绝不自动创建第二个 STT。
 */
@Component
public class DigitalHumanGrantService {

	/** K07：连接资格仅 audio 通道。 */
	public static final String CHANNEL_AUDIO = "audio";
	private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final String KEY_PREFIX = "dh:grant:";

	/** API15 响应（原文一次性返回，no-store）。 */
	public record ConnectionGrant(String grant, Instant expiresAt, String wsPath) {
	}

	/** INTERNAL07 wire 的执行资格（scope 固定 stage+invocation+inputHash）。 */
	public record ExecutionGrant(String grant, Instant expiresAt, Instant deadlineAt) {
	}

	/** INTERNAL05 响应（K07.2）。 */
	public record GrantBinding(String sessionId, long leaseEpoch, long contentEpoch, Instant expiresAt, String turnId,
			long turnEpoch, Instant firstFrameDeadlineAt) {
	}

	/** INTERNAL08 核销结果：owner 只在 Java 内部派生使用，不回 wrapper 错误。 */
	public record ExecutionRedemption(String owner, UUID invocationId, InvocationStage stage) {
	}

	private final ReactiveStringRedisTemplate redis;
	private final DatabaseClient db;
	private final DigitalHumanOperations operations;
	private final TransactionalOperator transactions;
	private final DigitalHumanInternalProperties properties;
	private final Clock clock;

	@org.springframework.beans.factory.annotation.Autowired
	public DigitalHumanGrantService(ReactiveStringRedisTemplate redis, DatabaseClient db,
			DigitalHumanOperations operations, TransactionalOperator transactions,
			DigitalHumanInternalProperties properties) {
		this(redis, db, operations, transactions, properties, Clock.systemUTC());
	}

	DigitalHumanGrantService(ReactiveStringRedisTemplate redis, DatabaseClient db, DigitalHumanOperations operations,
			TransactionalOperator transactions, DigitalHumanInternalProperties properties, Clock clock) {
		this.redis = redis;
		this.db = db;
		this.operations = operations;
		this.transactions = transactions;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * API15：签发连接资格。先验 owner/状态/租约/Origin（精确比对 allowlist），再落 Redis；channel 仅
	 * audio（K07：连接资格仅 audio）。
	 */
	public Mono<ConnectionGrant> issue(PersonalActor actor, UUID sessionId, long leaseEpoch, String channel,
			String origin) {
		if (!CHANNEL_AUDIO.equals(channel)) {
			return Mono.error(new IntelligenceException(422, "dh_invalid_input", "连接资格仅支持 audio 通道。"));
		}
		if (origin == null || properties.allowedAiOrigins().stream().noneMatch(origin::equals)) {
			return Mono.error(new IntelligenceException(403, "dh_origin_rejected", "来源不被允许。"));
		}
		return db
				.sql("SELECT state, lease_epoch FROM dh_session WHERE id = CAST(:id AS uuid)"
						+ " AND owner_account_id = :owner")
				.bind("id", sessionId.toString()).bind("owner", actor.accountId()).map(row -> row).one()
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")))
				.flatMap(session -> {
					String state = session.get("state", String.class);
					long currentLease = session.get("lease_epoch", Long.class);
					SessionState parsed = SessionState.valueOf(state);
					if (parsed.terminal()) {
						return Mono.error(new IntelligenceException(409, "dh_state_conflict", "会话已结束。"));
					}
					if (currentLease != leaseEpoch) {
						return Mono.error(new IntelligenceException(409, "dh_lease_stale", "会话控制权已变化，请刷新状态。"));
					}
					return issueConnectionGrant(actor.accountId(), sessionId, leaseEpoch, channel, origin);
				});
	}

	/** 公开端点共用的 owner/状态/租约校验（API16/17/40 在明确不可用前仍先验真实约束）。 */
	public Mono<Void> assertSessionLease(PersonalActor actor, UUID sessionId, long leaseEpoch) {
		return db
				.sql("SELECT state, lease_epoch FROM dh_session WHERE id = CAST(:id AS uuid)"
						+ " AND owner_account_id = :owner")
				.bind("id", sessionId.toString()).bind("owner", actor.accountId()).map(row -> row).one()
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")))
				.flatMap(session -> {
					String state = session.get("state", String.class);
					if (SessionState.valueOf(state).terminal()) {
						return Mono.error(new IntelligenceException(409, "dh_state_conflict", "会话已结束。"));
					}
					if (session.get("lease_epoch", Long.class) != leaseEpoch) {
						return Mono.error(new IntelligenceException(409, "dh_lease_stale", "会话控制权已变化，请刷新状态。"));
					}
					return Mono.empty();
				});
	}

	private Mono<ConnectionGrant> issueConnectionGrant(String owner, UUID sessionId, long leaseEpoch, String channel,
			String origin) {
		return issueGrant(properties.grantTtl(),
				Map.of("kind", "connection", "owner", owner, "sessionId", sessionId.toString(), "leaseEpoch",
						leaseEpoch, "channel", channel, "origin", origin))
				.map(ticket -> new ConnectionGrant(ticket.grant(), ticket.expiresAt(),
						"/api/digital-human/sessions/" + sessionId + "/audio"));
	}

	/** INTERNAL07 内部签发：执行资格绑定已持久 invocation（stage/inputHash 冻结进载荷）。 */
	public Mono<ExecutionGrant> issueExecution(String owner, UUID invocationId, UUID sessionId, InvocationStage stage,
			String inputHash, Instant deadlineAt) {
		return issueGrant(properties.executionGrantTtl(),
				Map.of("kind", "execution", "owner", owner, "invocationId", invocationId.toString(), "sessionId",
						sessionId == null ? "" : sessionId.toString(), "stage", stage.name(), "inputHash",
						Objects.requireNonNullElse(inputHash, "")))
				.map(ticket -> new ExecutionGrant(ticket.grant(), ticket.expiresAt(), deadlineAt));
	}

	private record IssuedTicket(String grant, Instant expiresAt) {
	}

	private Mono<IssuedTicket> issueGrant(java.time.Duration ttl, Map<String, Object> payload) {
		byte[] raw = new byte[32];
		RANDOM.nextBytes(raw);
		String grant = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
		Instant expiresAt = clock.instant().plus(ttl);
		Map<String, Object> body = new java.util.HashMap<>(payload);
		body.put("issuedAt", clock.instant().toString());
		body.put("expiresAt", expiresAt.toString());
		return Mono.fromCallable(() -> JSON.writeValueAsString(body))
				.flatMap(json -> redis.opsForValue().set(key(grant), json, ttl))
				.thenReturn(new IssuedTicket(grant, expiresAt));
	}

	/**
	 * INTERNAL05：单次核销 + 幂等创建 audio turn。GETDEL 抢票据（Redis 原子，只赢一次）→ 载荷校验 → DB
	 * 事务（operation + turn ON CONFLICT + epoch 分配 + session→listening）。票据缺失一律 409
	 * dh_grant_invalid 且不回 owner；过期 410；重放沿原 turn。
	 */
	public Mono<GrantBinding> consumeConnection(String grant, UUID sessionId, long leaseEpoch, String origin,
			UUID requestId, String format, Integer sampleRate, Integer channels) {
		if (!"pcm_s16le".equals(format) || sampleRate == null || sampleRate != 16000 || channels == null
				|| channels != 1) {
			return Mono.error(new IntelligenceException(422, "dh_invalid_input", "音频格式必须为 pcm_s16le/16000Hz/单声道。"));
		}
		Objects.requireNonNull(requestId, "requestId 必填");
		return redeem(grant).flatMap(payload -> {
			if (!"connection".equals(payload.get("kind")) || !sessionId.toString().equals(payload.get("sessionId"))
					|| !origin.equals(payload.get("origin")) || !CHANNEL_AUDIO.equals(payload.get("channel"))) {
				return Mono.error(grantInvalid());
			}
			long boundLease = Long.parseLong(String.valueOf(payload.get("leaseEpoch")));
			if (boundLease != leaseEpoch) {
				return Mono.error(new IntelligenceException(409, "dh_lease_stale", "会话控制权已变化。"));
			}
			Instant expiresAt = Instant.parse(String.valueOf(payload.get("expiresAt")));
			if (!expiresAt.isAfter(clock.instant())) {
				return Mono.error(new IntelligenceException(410, "dh_grant_expired", "连接资格已过期，请重新获取。"));
			}
			String owner = String.valueOf(payload.get("owner"));
			return createAudioTurn(owner, sessionId, requestId, leaseEpoch, expiresAt);
		});
	}

	/** INTERNAL08：执行资格单次核销；stage/invocation/inputHash 任一不符即 409（不回 owner）。 */
	public Mono<ExecutionRedemption> consumeExecution(String grant, UUID invocationId, InvocationStage stage,
			String inputHash) {
		return redeem(grant).flatMap(payload -> {
			boolean matches = "execution".equals(payload.get("kind"))
					&& invocationId.toString().equals(payload.get("invocationId"))
					&& stage.name().equals(payload.get("stage"))
					&& Objects.equals(Objects.requireNonNullElse(inputHash, ""), payload.get("inputHash"));
			if (!matches) {
				return Mono.error(grantInvalid());
			}
			Instant expiresAt = Instant.parse(String.valueOf(payload.get("expiresAt")));
			if (!expiresAt.isAfter(clock.instant())) {
				return Mono.error(new IntelligenceException(410, "dh_grant_expired", "执行资格已过期。"));
			}
			return Mono.just(new ExecutionRedemption(String.valueOf(payload.get("owner")), invocationId, stage));
		});
	}

	/** 撤销（会话结束/登出路径）：按原文删除；幂等。 */
	public Mono<Boolean> revoke(String grant) {
		return redis.opsForValue().delete(key(grant));
	}

	// ---------- 私有 ----------

	private Mono<Map<String, Object>> redeem(String grant) {
		if (grant == null || grant.isBlank() || grant.length() > 128) {
			return Mono.error(grantInvalid());
		}
		return redis.opsForValue().getAndDelete(key(grant)).flatMap(json -> Mono.fromCallable(() -> {
			@SuppressWarnings("unchecked")
			Map<String, Object> payload = JSON.readValue(json, Map.class);
			return payload;
		})).switchIfEmpty(Mono.error(grantInvalid()));
	}

	private static IntelligenceException grantInvalid() {
		// 刻意不含 owner/会话信息（K07：失败不返回 owner）。
		return new IntelligenceException(409, "dh_grant_invalid", "连接或执行资格无效。");
	}

	private String key(String grant) {
		return KEY_PREFIX + sha256(grant);
	}

	private static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception failure) {
			throw new IllegalStateException("SHA-256 不可用", failure);
		}
	}

	/**
	 * DB 侧幂等建 turn（K13.1）：先 operation 锚定 requestId，再 turn ON CONFLICT，重放回原 turn。
	 */
	private Mono<GrantBinding> createAudioTurn(String owner, UUID sessionId, UUID requestId, long leaseEpoch,
			Instant expiresAt) {
		PersonalActor actor = new PersonalActor(owner);
		String payloadHash = DigitalHumanOperations.canonicalHash(
				Map.of("inputKind", "audio", "sessionId", sessionId.toString(), "leaseEpoch", leaseEpoch));
		Mono<GrantBinding> body = operations.reserve(actor, OperationKind.turn_create, requestId, payloadHash, null)
				.flatMap(operation -> db.sql("""
						WITH alloc AS (
						    UPDATE dh_session SET next_turn_epoch = next_turn_epoch + 1,
						        state = CASE WHEN state = 'ready' THEN 'listening' ELSE state END,
						        state_entered_at = CASE WHEN state = 'ready' THEN now() ELSE state_entered_at END,
						        version = version + 1, updated_at = now()
						    WHERE id = CAST(:s AS uuid) AND owner_account_id = :owner AND lease_epoch = :lease
						      AND state IN ('ready','listening') AND cleanup_pending = false
						    RETURNING next_turn_epoch - 1 AS turn_epoch, content_epoch
						), ins AS (
						    INSERT INTO dh_turn(id, owner_account_id, session_id, request_id, turn_epoch,
						        input_kind, state, started_at)
						    SELECT CAST(:id AS uuid), :owner, CAST(:s AS uuid), CAST(:r AS uuid),
						        alloc.turn_epoch, 'audio', 'accepted', now()
						    FROM alloc
						    ON CONFLICT (session_id, request_id) DO NOTHING
						    RETURNING id::text, turn_epoch
						)
						SELECT ins.id AS id, ins.turn_epoch AS turn_epoch,
						       (SELECT content_epoch FROM alloc) AS content_epoch
						FROM ins
						""").bind("id", UUID.randomUUID().toString()).bind("owner", owner)
						.bind("s", sessionId.toString()).bind("r", requestId.toString()).bind("lease", leaseEpoch)
						.map(row -> new GrantBinding(sessionId.toString(), leaseEpoch,
								row.get("content_epoch", Long.class), expiresAt, row.get("id", String.class),
								row.get("turn_epoch", Long.class),
								clock.instant().plus(properties.firstFrameTimeout())))
						.one()
						.flatMap(binding -> operations
								.attachResource(UUID.fromString(operation.id()), UUID.fromString(binding.turnId()))
								.thenReturn(binding))
						.switchIfEmpty(Mono.defer(() -> findTurn(sessionId, requestId).flatMap(existingTurn -> {
							// 重放：沿原 turn 返回，不建第二个 STT、不复活终态。
							if (existingTurn.leaseEpoch() != leaseEpoch) {
								return Mono.error(new IntelligenceException(409, "dh_lease_stale", "会话控制权已变化。"));
							}
							return Mono.just(new GrantBinding(sessionId.toString(), leaseEpoch,
									existingTurn.contentEpoch(), expiresAt, existingTurn.turnId(),
									existingTurn.turnEpoch(), clock.instant().plus(properties.firstFrameTimeout())));
						})))
						// 分配失败（会话已结束/非 ready/listening/清理中）且无原 turn：确定性 409，
						// 不静默空返回（K01 迟到必须受控；#105G C105G-05 撤销矩阵先红后绿）。
						.switchIfEmpty(Mono
								.error(new IntelligenceException(409, "dh_state_conflict", "会话当前不可建立音频轮（状态或租约已变化）。"))));
		return transactions.transactional(body).onErrorMap(
				org.springframework.dao.DataIntegrityViolationException.class,
				conflict -> new IntelligenceException(409, "dh_state_conflict", "已有活动轮次，请先结束当前轮。"));
	}

	private record ExistingTurn(String turnId, long turnEpoch, long contentEpoch, long leaseEpoch) {
	}

	private Mono<ExistingTurn> findTurn(UUID sessionId, UUID requestId) {
		return db
				.sql("SELECT t.id::text AS id, t.turn_epoch, s.content_epoch, s.lease_epoch FROM dh_turn t"
						+ " JOIN dh_session s ON s.id = t.session_id WHERE t.session_id = CAST(:s AS uuid)"
						+ " AND t.request_id = CAST(:r AS uuid)")
				.bind("s", sessionId.toString()).bind("r", requestId.toString())
				.map(row -> new ExistingTurn(row.get("id", String.class), row.get("turn_epoch", Long.class),
						row.get("content_epoch", Long.class), row.get("lease_epoch", Long.class)))
				.one();
	}
}
