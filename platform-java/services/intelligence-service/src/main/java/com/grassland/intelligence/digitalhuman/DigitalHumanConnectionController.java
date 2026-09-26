package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanGrantService.ConnectionGrant;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 连接资格与媒体面公开端点（任务书 #105D C105D-02 / 共享契约 K03 API15/16/17/40）。
 *
 * <p>
 * API15 完整落地：一次性 30 秒连接资格（原文只在响应出现一次，no-store；服务端只存 SHA256）。API16 （C105X-03 /
 * 任务书 #105fix-1）接通 INTERNAL03 真实中继：校验链后经 {@link DigitalHumanRuntimeClient} 送
 * runtime offer，answer 成功才 connectReady（connecting→ready 单向 CAS）并返回 200
 * {sdp,type,mediaEpoch,iceServers}；runtime 未配置/不可达维持 503 dh_runtime_unavailable
 * fail-closed。 API17/40 建立完整入参校验（owner/lease/形状）后<b>明确 503
 * 不可用</b>——接线随真实档后续阶段落地， 不以假成功占位。
 */
@RestController
public class DigitalHumanConnectionController {

	private static final com.fasterxml.jackson.databind.ObjectMapper ICE_JSON = new com.fasterxml.jackson.databind.ObjectMapper();

	private final DigitalHumanAuthorization authorization;
	private final DigitalHumanGrantService grants;
	private final DigitalHumanSessionService sessions;
	private final DigitalHumanRuntimeClient runtime;
	private final java.util.List<Map<String, Object>> iceServers;

	public DigitalHumanConnectionController(DigitalHumanAuthorization authorization, DigitalHumanGrantService grants,
			DigitalHumanSessionService sessions, DigitalHumanRuntimeClient runtime,
			@org.springframework.beans.factory.annotation.Value("${digital-human.turn.ice-servers-json:[]}") String iceServersJson) {
		this.authorization = authorization;
		this.grants = grants;
		this.sessions = sessions;
		this.runtime = runtime;
		this.iceServers = parseIceServers(iceServersJson);
	}

	/**
	 * E13：ice-servers-json 非法（非 JSON / 非 [{urls,...}] 形状）→ 启动 fail-fast，不静默空数组 （生产
	 * TURN 打开时部署注入完整数组，口径同运行手册 §7.2）。
	 */
	private static java.util.List<Map<String, Object>> parseIceServers(String json) {
		if (json == null || json.isBlank() || "[]".equals(json.trim())) {
			return java.util.List.of();
		}
		try {
			return ICE_JSON.readValue(json,
					ICE_JSON.getTypeFactory().constructCollectionType(java.util.List.class, java.util.Map.class));
		} catch (Exception failure) {
			throw new IllegalStateException("digital-human.turn.ice-servers-json 解析失败（fail-fast）", failure);
		}
	}

	// API15：一次性连接资格（原文一次性返回，响应 no-store）。
	@PostMapping("/api/digital-human/sessions/{id}/connection-grants")
	public Mono<ResponseEntity<Map<String, Object>>> issueGrant(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			var request = DigitalHumanOperations.parseStrict(body, GrantRequest.class);
			if (request.leaseEpoch() == null) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "leaseEpoch 必填。"));
			}
			String channel = request.channel() == null ? "audio" : request.channel();
			return grants
					.issue(actor, id, request.leaseEpoch(), channel, exchange.getRequest().getHeaders().getOrigin())
					.map(this::grantResponse);
		});
	}

	// API16：WebRTC offer 中继（C105X-03 接通 INTERNAL03）：全字段严格解析（K00 拒未知字段）→
	// lease 断言（401/404/409 语义与 validateLeaseRequest 同款，经 assertSessionLease）→
	// runtime
	// offer → connectReady 通过后才返回 answer；runtime 错误（IntelligenceException 已带既有
	// status/code，如 dh_lease_stale/dh_media_epoch_stale→409）自然透传，未配置/不可达维持 503。
	@PostMapping("/api/digital-human/sessions/{id}/webrtc/offer")
	public Mono<ResponseEntity<Map<String, Object>>> webrtcOffer(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest())
				.flatMap(actor -> Mono.fromCallable(() -> DigitalHumanOperations.parseStrict(body, OfferRequest.class))
						.flatMap(request -> validateOfferShape(request)
								.then(grants.assertSessionLease(actor, id, request.leaseEpoch()))
								.then(runtime.webrtcOffer(id.toString(), request.leaseEpoch(), request.mediaEpoch(),
										request.sdp()))
								.flatMap(answer -> sessions.connectReady(actor, id).thenReturn(okAnswer(answer)))));
	}

	private Mono<Void> validateOfferShape(OfferRequest request) {
		return Mono.defer(() -> {
			if (request.mediaEpoch() == null || request.sdp() == null || request.sdp().isBlank()
					|| !"offer".equals(request.type())) {
				throw new IntelligenceException(422, "dh_invalid_input", "mediaEpoch/sdp/type=offer 必填。");
			}
			return Mono.empty();
		});
	}

	private ResponseEntity<Map<String, Object>> okAnswer(DigitalHumanRuntimeClient.RtcAnswer answer) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(Map.of("success", true, "data", Map.of("sdp", answer.sdp(), "type", answer.type(), "mediaEpoch",
						answer.mediaEpoch(), "iceServers", iceServers)));
	}

	// API17：媒体就绪确认（仅 runtime ready 且客户端收到 track 后接受；随 C105D-05 落地）。
	@PostMapping("/api/digital-human/sessions/{id}/media-ready")
	public Mono<ResponseEntity<Map<String, Object>>> mediaReady(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest())
				.flatMap(actor -> validateLeaseRequest(actor, id, body, true)).then(Mono.error(runtimeUnavailable()));
	}

	// API40：播放重置（丢弃旧 peer、mediaEpoch 递增；runtime 侧 peer 生命周期随 C105D-05 落地）。
	@PostMapping("/api/digital-human/sessions/{id}/playback-reset")
	public Mono<ResponseEntity<Map<String, Object>>> playbackReset(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest())
				.flatMap(actor -> validateLeaseRequest(actor, id, body, false)).then(Mono.error(runtimeUnavailable()));
	}

	// ---------- 私有 ----------

	private Mono<Void> validateLeaseRequest(DigitalHumanAuthorization.PersonalActor actor, UUID id, String body,
			boolean requireRequestId) {
		return Mono.defer(() -> {
			var request = DigitalHumanOperations.parseStrict(body, LeaseAndEpochRequest.class);
			if (request.leaseEpoch() == null || (requireRequestId && request.requestId() == null)) {
				throw new IntelligenceException(422, "dh_invalid_input",
						requireRequestId ? "leaseEpoch/requestId 必填。" : "leaseEpoch 必填。");
			}
			return grants.assertSessionLease(actor, id, request.leaseEpoch());
		});
	}

	private static IntelligenceException runtimeUnavailable() {
		return new IntelligenceException(503, "dh_runtime_unavailable", "媒体面随运行时接线开放，当前不可用。");
	}

	private ResponseEntity<Map<String, Object>> grantResponse(ConnectionGrant grant) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("success", true, "data",
				Map.of("grant", grant.grant(), "expiresAt", grant.expiresAt().toString(), "wsPath", grant.wsPath())));
	}

	/** API15 请求体（K00：拒绝未知字段）。 */
	public record GrantRequest(Long leaseEpoch, String channel) {
	}

	public record LeaseAndEpochRequest(UUID requestId, Long leaseEpoch) {
	}

	/** API16 请求体（K00：拒绝未知字段；sdp 上限 65536 由 runtime 端既有校验承担，Java 不重复实现）。 */
	public record OfferRequest(UUID requestId, Long leaseEpoch, Long mediaEpoch, String sdp, String type) {
	}
}
