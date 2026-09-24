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
 * API15 完整落地：一次性 30 秒连接资格（原文只在响应出现一次，no-store；服务端只存 SHA256）。API16/17/40
 * 建立完整入参校验（owner/lease/形状）后<b>明确 503 不可用</b>——runtime 控制面与 WebRTC 接线随 C105D-05
 * 落地，不以假成功占位。
 */
@RestController
public class DigitalHumanConnectionController {

	private final DigitalHumanAuthorization authorization;
	private final DigitalHumanGrantService grants;

	public DigitalHumanConnectionController(DigitalHumanAuthorization authorization, DigitalHumanGrantService grants) {
		this.authorization = authorization;
		this.grants = grants;
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

	// API16：WebRTC offer 中继（runtime 接线随 C105D-05；先完整校验再明确不可用）。
	@PostMapping("/api/digital-human/sessions/{id}/webrtc/offer")
	public Mono<ResponseEntity<Map<String, Object>>> webrtcOffer(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest())
				.flatMap(actor -> validateLeaseRequest(actor, id, body, true)).then(Mono.error(runtimeUnavailable()));
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
}
