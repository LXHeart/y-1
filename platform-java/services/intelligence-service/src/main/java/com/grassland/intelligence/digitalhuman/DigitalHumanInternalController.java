package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.ai.byok.ByokRoutingService;
import com.grassland.intelligence.digitalhuman.DigitalHumanGrantService.ExecutionGrant;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationStage;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * 内部端点 handler 容器（任务书 #105D C105D-02 / 共享契约 K07.2、K13.2）。
 *
 * <p>
 * 刻意<b>不标 {@code @RestController}</b>：本类只被 {@link DigitalHumanInternalServer}
 * 的独立 9143 mTLS RouterFunction 引用，绝不进入主 WebFlux 路由（主端口访问 internal 路径 =
 * 404）。身份来自 mTLS 握手证书 （SAN allowlist 在 server 的 TrustManager 强制），不读任何转发证书
 * header；grant/SDP/key 不进日志。
 *
 * <p>
 * 事件类型 allowlist 按 K13.1；INTERNAL07 建立 C105D-01 的 reserve/prepare
 * 经济链并发放执行资格（预留按 K08.1 固定：LLM 输入上界 64KiB/4、输出 1024；STT 60 秒；TTS 90 秒）；实际
 * STT/LLM/TTS 桥在 C105D-03/04 接线前 INTERNAL08 明确 503
 * 且<b>不核销</b>执行资格（确定性失败不烧一次性凭据）； INTERNAL14/15（render）按 §9.1 定义认证与类型、未装配明确不可用。
 */
@Component
public class DigitalHumanInternalController {

	/** K13.1：runtime 可上报的事件类型（文本/媒体生成事件在 K06 白名单内随阶段补充）。 */
	private static final Set<String> RUNTIME_EVENT_TYPES = Set.of("runtime.ready", "turn.started", "turn.failed",
			"speech.segment", "media.reset.ack", "runtime.closed", "runtime.error");
	private static final ObjectMapper JSON = new ObjectMapper();

	private final DigitalHumanGrantService grants;
	private final DigitalHumanEventService events;
	private final DigitalHumanInvocationService invocations;
	private final DigitalHumanInvocationRepository invocationRepository;
	private final ByokRoutingService routing;
	private final DatabaseClient db;
	private final DigitalHumanAvatarService avatars;
	private final DigitalHumanRecordingService recordings;

	public DigitalHumanInternalController(DigitalHumanGrantService grants, DigitalHumanEventService events,
			DigitalHumanInvocationService invocations, DigitalHumanInvocationRepository invocationRepository,
			ByokRoutingService routing, DatabaseClient db, DigitalHumanAvatarService avatars,
			DigitalHumanRecordingService recordings) {
		this.grants = grants;
		this.events = events;
		this.invocations = invocations;
		this.invocationRepository = invocationRepository;
		this.routing = routing;
		this.db = db;
		this.avatars = avatars;
		this.recordings = recordings;
	}

	/** INTERNAL05：grant 单次核销 + 幂等 audio turn。 */
	public Mono<ServerResponse> consumeGrant(ServerRequest request) {
		return request.bodyToMono(String.class).flatMap(body -> {
			Map<String, Object> input = readMap(body);
			String grant = text(input, "grant");
			UUID sessionId = uuid(input, "sessionId");
			Long leaseEpoch = longValue(input, "leaseEpoch");
			String origin = text(input, "origin");
			UUID requestId = uuid(input, "requestId");
			String format = text(input, "format");
			Integer sampleRate = intValue(input, "sampleRate");
			Integer channels = intValue(input, "channels");
			if (leaseEpoch == null) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "leaseEpoch 必填。"));
			}
			return grants
					.consumeConnection(grant, sessionId, leaseEpoch, origin, requestId, format, sampleRate, channels)
					.flatMap(binding -> ServerResponse.ok().header("Cache-Control", "no-store")
							.bodyValue(Map.of("sessionId", binding.sessionId(), "leaseEpoch", binding.leaseEpoch(),
									"contentEpoch", binding.contentEpoch(), "expiresAt", binding.expiresAt().toString(),
									"turnId", binding.turnId(), "turnEpoch", binding.turnEpoch(),
									"firstFrameDeadlineAt", binding.firstFrameDeadlineAt().toString())));
		});
	}

	/** INTERNAL06：runtime 事件入 dh_event（Java 分配 seq，eventId 去重，类型 allowlist）。 */
	public Mono<ServerResponse> reportEvent(ServerRequest request) {
		return request.bodyToMono(String.class).flatMap(body -> {
			Map<String, Object> input = readMap(body);
			UUID eventId = uuid(input, "eventId");
			UUID sessionId = uuid(input, "sessionId");
			Long leaseEpoch = longValue(input, "leaseEpoch");
			String type = text(input, "type");
			@SuppressWarnings("unchecked")
			Map<String, Object> payload = input.get("payload") instanceof Map found ? found : Map.of();
			if (eventId == null || sessionId == null || leaseEpoch == null || type == null) {
				return Mono.error(
						new IntelligenceException(422, "dh_invalid_input", "eventId/sessionId/leaseEpoch/type 必填。"));
			}
			if (!RUNTIME_EVENT_TYPES.contains(type)) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "事件类型不被接受。"));
			}
			return events.append(sessionId.toString(), eventId, type, leaseEpoch, payload)
					.flatMap(result -> ServerResponse.ok().header("Cache-Control", "no-store").bodyValue(Map
							.of("eventId", result.eventId(), "seq", result.seq(), "accepted", !result.duplicated())));
		});
	}

	/**
	 * INTERNAL07：创建阶段 invocation（经济链落库+prepare）并附执行资格。owner 由 session 行派生，不由
	 * runtime 声明；preview/render 阶段在本卡明确不可用（D-04/D-05 落地）。
	 */
	public Mono<ServerResponse> createInvocation(ServerRequest request) {
		return request.bodyToMono(String.class).flatMap(body -> {
			Map<String, Object> input = readMap(body);
			String stageText = text(input, "stage");
			Integer segmentIndex = intValue(input, "segmentIndex");
			String inputHash = text(input, "inputHash");
			UUID sessionId = uuid(input, "sessionId");
			UUID turnId = uuid(input, "turnId");
			if (stageText == null || segmentIndex == null || inputHash == null) {
				return Mono
						.error(new IntelligenceException(422, "dh_invalid_input", "stage/segmentIndex/inputHash 必填。"));
			}
			InvocationStage stage;
			try {
				stage = InvocationStage.valueOf(stageText);
			} catch (IllegalArgumentException bad) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "未知 stage。"));
			}
			if (stage == InvocationStage.preview || stage == InvocationStage.render) {
				return unavailable(stage + " 阶段内部创建随 C105D-04/05 落地。");
			}
			if (sessionId == null || turnId == null) {
				return Mono.error(
						new IntelligenceException(422, "dh_invalid_input", stage + " 阶段必须挂 sessionId 与 turnId。"));
			}
			return ownerOfTurn(sessionId, turnId).flatMap(owner -> routing
					.resolveProvider(null, owner, DigitalHumanInvocationService.capabilityFor(stage), false)
					.flatMap(provider -> invocations.reserve(new DigitalHumanAuthorization.PersonalActor(owner),
							sessionId, turnId, stage, turnId, segmentIndex, provider, inputHash,
							Instant.now().plusSeconds(90), estimateInputTokens(stage), estimateOutputTokens(stage),
							estimateSeconds(stage)))
					.flatMap(row -> invocations.prepare(UUID.fromString(row.id())).flatMap(prepared -> grants
							.issueExecution(owner, UUID.fromString(row.id()), sessionId, stage, inputHash,
									prepared.deadlineAt())
							.map(execution -> wire(row, execution, inputHash)).flatMap(
									wire -> ServerResponse.ok().header("Cache-Control", "no-store").bodyValue(wire)))));
		});
	}

	/**
	 * INTERNAL08：执行资格核销与派发前置校验；实际 STT/LLM/TTS 桥随 C105D-03/04 接线——当前明确 503
	 * 不可用，且<b>不核销</b>执行资格（确定性失败不烧一次性凭据）、不 claimDispatch。
	 */
	public Mono<ServerResponse> executeInvocation(ServerRequest request) {
		String invocationId = request.pathVariable("id");
		return request.bodyToMono(String.class).flatMap(body -> {
			Map<String, Object> input = readMap(body);
			String auth = request.headers().firstHeader("Authorization");
			String grant = auth != null && auth.startsWith("Bearer ") ? auth.substring("Bearer ".length()) : null;
			String inputHash = text(input, "inputHash");
			if (grant == null || inputHash == null) {
				return Mono.error(new IntelligenceException(401, "dh_grant_invalid", "执行资格无效。"));
			}
			return invocationRepository.findById(UUID.fromString(invocationId))
					.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "调用不存在。")))
					.flatMap(row -> unavailable("执行桥随 C105D-03/04 落地，当前明确不可用。"));
		});
	}

	/** INTERNAL14（K14 render 控制）：认证与类型已定义，未装配明确不可用（C105D-05）。 */
	public Mono<ServerResponse> renderControl(ServerRequest request) {
		return unavailable("render 控制随 C105D-05 落地。");
	}

	/** INTERNAL15（K14 短期媒体资格续签）：认证与类型已定义，未装配明确不可用（C105D-05）。 */
	public Mono<ServerResponse> renderConnectionGrant(ServerRequest request) {
		return unavailable("render 媒体资格随 C105D-05 落地。");
	}

	/**
	 * INTERNAL11（#105F）：runtime→Java 媒体产物回执。owner 从待处理 dh_avatar/dh_recording 行
	 * 解析（不采信 runtime 声明身份）；迟到结果由资源状态墓碑拦截，accepted=false 如实返回。
	 */
	public Mono<ServerResponse> reportArtifact(ServerRequest request) {
		return request.bodyToMono(String.class).flatMap(body -> {
			Map<String, Object> input = readMap(body);
			UUID eventId = uuid(input, "eventId");
			String kind = text(input, "kind");
			UUID resourceId = uuid(input, "resourceId");
			Integer revision = intValue(input, "revision");
			String errorCode = text(input, "errorCode");
			String manifest = null;
			if (input.containsKey("manifest") && input.get("manifest") != null) {
				try {
					manifest = JSON.writeValueAsString(input.get("manifest"));
				} catch (Exception invalid) {
					return Mono.error(new IntelligenceException(422, "dh_invalid_input", "manifest 结构不合法。"));
				}
			}
			if (eventId == null || kind == null || resourceId == null || revision == null) {
				return Mono.error(
						new IntelligenceException(422, "dh_invalid_input", "eventId/kind/resourceId/revision 必填。"));
			}
			if (!"avatar".equals(kind) && !"recording".equals(kind)) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "未知产物类型。"));
			}
			if ("avatar".equals(kind)) {
				return avatars.applyAvatarArtifact(resourceId, revision, manifest, errorCode)
						.flatMap(accepted -> ServerResponse.ok().header("Cache-Control", "no-store")
								.bodyValue(Map.of("accepted", accepted.accepted(), "reason", accepted.reason())));
			}
			return recordings.applyRecordingArtifact(resourceId, revision, manifest, errorCode)
					.flatMap(accepted -> ServerResponse.ok().header("Cache-Control", "no-store")
							.bodyValue(Map.of("accepted", accepted.accepted(), "reason", accepted.reason())));
		});
	}

	/**
	 * 内部面统一错误翻译：独立 RouterFunction 不经过主 WebFlux 的 @ControllerAdvice——
	 * IntelligenceException 在此映射为稳定 JSON 信封（code/错误文案），其余归并 503。
	 */
	public static Mono<ServerResponse> handled(Mono<ServerResponse> handler) {
		return handler
				.onErrorResume(IntelligenceException.class,
						error -> ServerResponse.status(error.status()).header("Cache-Control", "no-store")
								.bodyValue(Map.of("success", false, "error", error.getMessage(), "code",
										error.code() == null ? "dh_error" : error.code())))
				.onErrorResume(error -> ServerResponse.status(503).header("Cache-Control", "no-store")
						.bodyValue(Map.of("success", false, "error", "内部处理失败。", "code", "dh_runtime_unavailable")));
	}

	// ---------- 私有 ----------

	private static Map<String, Object> wire(InvocationRow row, ExecutionGrant execution, String inputHash) {
		return Map.of("invocationId", row.id(), "grant", execution.grant(), "expiresAt",
				execution.expiresAt().toString(), "deadlineAt", execution.deadlineAt().toString(), "inputHash",
				inputHash);
	}

	/** K08.1 固定预留（本卡建立经济链；D-03/04 按 bridge 实际输入精化）。 */
	private static int estimateInputTokens(InvocationStage stage) {
		return stage == InvocationStage.llm ? 65536 / 4 : 0;
	}

	private static int estimateOutputTokens(InvocationStage stage) {
		return stage == InvocationStage.llm ? 1024 : 0;
	}

	private static int estimateSeconds(InvocationStage stage) {
		return switch (stage) {
			case stt -> 60;
			case tts -> 90;
			default -> 0;
		};
	}

	private static Mono<ServerResponse> unavailable(String message) {
		return ServerResponse.status(503).header("Cache-Control", "no-store")
				.bodyValue(Map.of("success", false, "error", message, "code", "dh_runtime_unavailable"));
	}

	private Mono<String> ownerOfTurn(UUID sessionId, UUID turnId) {
		return db.sql("SELECT owner_account_id AS owner FROM dh_session WHERE id = CAST(:s AS uuid)")
				.bind("s", sessionId.toString()).map(row -> row.get("owner", String.class)).one()
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "会话不存在。")))
				.flatMap(owner -> db
						.sql("SELECT id FROM dh_turn WHERE id = CAST(:t AS uuid)"
								+ " AND session_id = CAST(:s AS uuid)")
						.bind("t", turnId.toString()).bind("s", sessionId.toString())
						.map(row -> row.get("id", String.class)).one()
						.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "轮次不存在。")))
						.thenReturn(owner));
	}

	private static Map<String, Object> readMap(String body) {
		try {
			return JSON.readValue(
					body == null ? "{}".getBytes(StandardCharsets.UTF_8) : body.getBytes(StandardCharsets.UTF_8),
					new TypeReference<Map<String, Object>>() {
					});
		} catch (Exception failure) {
			throw new IntelligenceException(422, "dh_invalid_input", "请求体格式不正确。");
		}
	}

	private static String text(Map<String, Object> input, String key) {
		Object value = input.get(key);
		return value instanceof String found ? found : null;
	}

	private static UUID uuid(Map<String, Object> input, String key) {
		String value = text(input, key);
		try {
			return value == null ? null : UUID.fromString(value);
		} catch (IllegalArgumentException bad) {
			throw new IntelligenceException(422, "dh_invalid_input", key + " 不是合法 UUID。");
		}
	}

	private static Long longValue(Map<String, Object> input, String key) {
		Object value = input.get(key);
		return value instanceof Number number ? number.longValue() : null;
	}

	private static Integer intValue(Map<String, Object> input, String key) {
		Object value = input.get(key);
		return value instanceof Number number ? number.intValue() : null;
	}
}
