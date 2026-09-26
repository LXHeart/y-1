package com.grassland.intelligence.hypit.execution;

import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.execution.HypitExternalExecutionBridge.PrepareRequest;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.hypit.execution.HypitExternalExecutionBridge.ExecutionPermit;
import com.grassland.intelligence.hypit.execution.HypitExternalExecutionBridge.Settlement;
import com.grassland.intelligence.security.IntelligenceException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * sidecar → Java 的外部 Need 授权/回执端点（任务书 #107-1 C107-07 / §6.3）。
 *
 * <p>
 * {@code POST /internal/hypit/executions/{prepare,complete,fail,cancel}}： 内部
 * Bearer token（服务端配置，非用户身份）鉴权；prepare 响应是短时执行许可与 凭据引用，不向浏览器曝光。回执幂等：重复 callback
 * 不再结算。
 */
@RestController
public class HypitInternalExecutionController {

	private final HypitProperties properties;
	private final HypitExternalExecutionBridge bridge;

	public HypitInternalExecutionController(HypitProperties properties, HypitExternalExecutionBridge bridge) {
		this.properties = properties;
		this.bridge = bridge;
	}

	private void requireInternalToken(String authorization) {
		if (!properties.enabled() || properties.internalToken().length() < 32) {
			throw new IntelligenceException(HttpStatus.SERVICE_UNAVAILABLE.value(), "hypit_disabled", "hypit 内部执行桥未启用");
		}
		if (authorization == null || !authorization.equals("Bearer " + properties.internalToken())) {
			throw new IntelligenceException(HttpStatus.UNAUTHORIZED.value(), "hypit_unauthenticated", "内部执行桥 token 无效");
		}
	}

	public record PrepareBody(String operationId, String projectId, String jobId, String buildId, String needId,
			String capability, String model, String endpointId, String requestHash, String grantId,
			BigDecimal estimatedCost) {
	}

	public record ReceiptBody(String operationId, String state, Map<String, Object> receipt, BigDecimal actualCost,
			String detail) {
	}

	@PostMapping("/internal/hypit/executions/prepare")
	public Mono<ResponseEntity<Map<String, Object>>> prepare(
			@RequestHeader(value = "Authorization", required = false) String authorization,
			@RequestBody PrepareBody body) {
		return Mono.fromCallable(() -> {
			requireInternalToken(authorization);
			UUID operationId = requireUuid(body.operationId(), "operationId");
			UUID grantId = requireUuid(body.grantId(), "grantId");
			return new PrepareRequest(operationId, optionalUuid(body.projectId()), optionalUuid(body.jobId()),
					optionalUuid(body.buildId()), requireText(body.needId(), "needId"),
					requireText(body.capability(), "capability"), requireText(body.model(), "model"),
					requireText(body.endpointId(), "endpointId"), requireText(body.requestHash(), "requestHash"),
					grantId, body.estimatedCost());
		}).flatMap(bridge::prepare)
				.map(permit -> ResponseEntity.ok().body(Map.of("permitId", (Object) permit.permitId().toString(),
						"operationId", permit.operationId().toString(), "expiresAt", permit.expiresAt().toString(),
						"credentialRef", Map.of("store", permit.credentialStore(), "key", permit.credentialKey()))));
	}

	@PostMapping("/internal/hypit/executions/complete")
	public Mono<ResponseEntity<Map<String, Object>>> complete(
			@RequestHeader(value = "Authorization", required = false) String authorization,
			@RequestBody ReceiptBody body) {
		return Mono.fromCallable(() -> {
			requireInternalToken(authorization);
			return requireUuid(body.operationId(), "operationId");
		}).flatMap(operationId -> bridge.complete(operationId,
				body.receipt() == null ? null : HypitJson.write(body.receipt()), body.actualCost()))
				.map(HypitInternalExecutionController::settled);
	}

	@PostMapping("/internal/hypit/executions/fail")
	public Mono<ResponseEntity<Map<String, Object>>> fail(
			@RequestHeader(value = "Authorization", required = false) String authorization,
			@RequestBody ReceiptBody body) {
		return Mono.fromCallable(() -> {
			requireInternalToken(authorization);
			return requireUuid(body.operationId(), "operationId");
		}).flatMap(operationId -> bridge.fail(operationId,
				body.receipt() == null ? null : HypitJson.write(body.receipt()), body.detail()))
				.map(HypitInternalExecutionController::settled);
	}

	@PostMapping("/internal/hypit/executions/cancel")
	public Mono<ResponseEntity<Map<String, Object>>> cancel(
			@RequestHeader(value = "Authorization", required = false) String authorization,
			@RequestBody ReceiptBody body) {
		return Mono.fromCallable(() -> {
			requireInternalToken(authorization);
			return requireUuid(body.operationId(), "operationId");
		}).flatMap(operationId -> bridge.cancel(operationId, body.detail()))
				.map(HypitInternalExecutionController::settled);
	}

	private static ResponseEntity<Map<String, Object>> settled(Settlement result) {
		return ResponseEntity.ok(Map.of("settled", result.settled(), "state", result.execution().state()));
	}

	private static UUID requireUuid(String value, String field) {
		try {
			return UUID.fromString(requireText(value, field));
		} catch (IllegalArgumentException error) {
			throw new IntelligenceException(HttpStatus.BAD_REQUEST.value(), "hypit_invalid_input", field + " 必须是 UUID");
		}
	}

	private static UUID optionalUuid(String value) {
		return value == null || value.isBlank() ? null : UUID.fromString(value);
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IntelligenceException(HttpStatus.BAD_REQUEST.value(), "hypit_invalid_input", field + " 必填");
		}
		return value;
	}
}
