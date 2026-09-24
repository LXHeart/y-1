package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanArtifactService.DownloadResponse;
import com.grassland.intelligence.digitalhuman.DigitalHumanArtifactService.SaveOutcome;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecordingService.StartResult;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecordingService.StopResult;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 录制端点（任务书 #105F C105F-02/C105F-03 / K03 API33～37）。Controller 只装配：鉴权→净化→服务→信封。
 *
 * <p>
 * 请求体严格解码（拒绝未知字段，K00）；API34 按状态回 202 finalizing / 200 终态；API36 认证流 200/206（单区间
 * Range，切片与 416 判定在服务层）；API37 202 新保存 / 200 同键重放同一 succeeded； 所有正文
 * {@code Cache-Control: no-store}。
 */
@RestController
@RequestMapping("/api/digital-human")
public class DigitalHumanRecordingController {

	private final DigitalHumanAuthorization authorization;
	private final DigitalHumanRecordingService recordings;
	private final DigitalHumanArtifactService artifacts;

	public DigitalHumanRecordingController(DigitalHumanAuthorization authorization,
			DigitalHumanRecordingService recordings, DigitalHumanArtifactService artifacts) {
		this.authorization = authorization;
		this.recordings = recordings;
		this.artifacts = artifacts;
	}

	// API33
	@PostMapping("/sessions/{id}/recordings")
	public Mono<ResponseEntity<Map<String, Object>>> start(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			StartRequest request = DigitalHumanOperations.parseStrict(body, StartRequest.class);
			if (request.requestId() == null) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "requestId 必填。"));
			}
			if (request.leaseEpoch() == null) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "leaseEpoch 必填。"));
			}
			return recordings
					.start(actor, id, request.requestId(), request.leaseEpoch(),
							Boolean.TRUE.equals(request.acknowledgement()))
					.map(result -> ResponseEntity.status(result.createdNow() ? HttpStatus.ACCEPTED : HttpStatus.OK)
							.cacheControl(noStore()).body(Map.of("success", true, "data", result.recording())));
		});
	}

	// API34：202 finalizing 或 200 终态。
	@PostMapping("/recordings/{id}/stop")
	public Mono<ResponseEntity<Map<String, Object>>> stop(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			StopRequest request = DigitalHumanOperations.parseStrict(body, StopRequest.class);
			if (request.requestId() == null) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "requestId 必填。"));
			}
			return recordings.stop(actor, id, request.requestId()).map(result -> ResponseEntity
					.status("finalizing".equals(result.recording().state()) ? HttpStatus.ACCEPTED : HttpStatus.OK)
					.cacheControl(noStore()).body(Map.of("success", true, "data", result.recording())));
		});
	}

	// API35
	@GetMapping("/recordings/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable UUID id, ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> recordings.get(actor, id))
				.map(recording -> ResponseEntity.ok().cacheControl(noStore())
						.body(Map.of("success", true, "data", recording)));
	}

	// API36：200 全量 / 206 单区间（416 由服务层抛出，全局异常处理回错误信封）。
	@GetMapping("/recordings/{id}/download")
	public Mono<ResponseEntity<byte[]>> download(@PathVariable UUID id, @RequestParam String artifact,
			@RequestHeader(value = "Range", required = false) String range, ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest())
				.flatMap(actor -> artifacts.download(actor, id, artifact, range)).map(response -> {
					ResponseEntity.BodyBuilder builder = ResponseEntity
							.status(response.rangeStart() == null ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT)
							.header("Content-Type", response.contentType())
							.header("Content-Disposition", "attachment; filename=\"" + response.filename() + "\"")
							.header("Accept-Ranges", "bytes").cacheControl(noStore());
					if (response.rangeStart() != null) {
						builder.header("Content-Range", "bytes " + response.rangeStart() + "-" + response.rangeEnd()
								+ "/" + response.totalSize());
					}
					return builder.body(response.body());
				});
	}

	// API37：202 新保存 / 200 同键重放同一 succeeded（resultRef=assetId）。
	@PostMapping("/recordings/{id}/save")
	public Mono<ResponseEntity<Map<String, Object>>> save(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			SaveRequest request = DigitalHumanOperations.parseStrict(body, SaveRequest.class);
			if (request.requestId() == null) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "requestId 必填。"));
			}
			if (request.title() == null || request.includeSubtitles() == null) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "title 与 includeSubtitles 必填。"));
			}
			return artifacts.save(actor, id, request.requestId(), request.title(), request.includeSubtitles())
					.map(outcome -> ResponseEntity.status(outcome.completedNow() ? HttpStatus.ACCEPTED : HttpStatus.OK)
							.cacheControl(noStore()).body(Map.of("success", true, "data", outcome.operation())));
		});
	}

	private static CacheControl noStore() {
		return CacheControl.noStore();
	}

	/** API33 请求：acknowledgement=录制字幕授权（K09：不等于同意保存整个聊天）。 */
	public record StartRequest(UUID requestId, Long leaseEpoch, Boolean acknowledgement) {
	}

	public record StopRequest(UUID requestId) {
	}

	/** API37 请求：title 1～100（服务层校验）；includeSubtitles 必填显式选择。 */
	public record SaveRequest(UUID requestId, String title, Boolean includeSubtitles) {
	}
}
