package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.Profile;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.ProfileInput;
import com.grassland.intelligence.digitalhuman.DigitalHumanProfileService.CreateResult;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 角色档案端点（任务书 #105B C105B-03 / K03 API02～API06）。Controller 只装配：鉴权→净化→服务→信封。
 *
 * <p>
 * 请求体严格解码（拒绝未知字段，K00）；DELETE 带 JSON requestId（K03：不用 204 避免信封歧义）。所有正文
 * {@code Cache-Control: no-store}。同键同体重放：POST 201 → 重放 200 原 Profile。
 */
@RestController
@RequestMapping("/api/digital-human")
public class DigitalHumanProfileController {

	private final DigitalHumanAuthorization authorization;
	private final DigitalHumanProfileService profiles;

	public DigitalHumanProfileController(DigitalHumanAuthorization authorization, DigitalHumanProfileService profiles) {
		this.authorization = authorization;
		this.profiles = profiles;
	}

	// API02
	@GetMapping("/profiles")
	public Mono<ResponseEntity<Map<String, Object>>> list(
			@RequestParam(name = "cursor", required = false) String cursor,
			@RequestParam(name = "limit", required = false) Integer limit, ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest())
				.flatMap(actor -> profiles.list(actor, cursor, limit)).map(page -> envelope(page));
	}

	// API03
	@PostMapping("/profiles")
	public Mono<ResponseEntity<Map<String, Object>>> create(@RequestBody String body, ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			CreateRequest request = DigitalHumanOperations.parseStrict(body, CreateRequest.class);
			if (request.requestId() == null) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "requestId 必填。"));
			}
			ProfileInput input = DigitalHumanProfileService.sanitize(request.toInput());
			return profiles.create(actor, input, request.requestId())
					.map(result -> ResponseEntity.status(result.createdNow() ? HttpStatus.CREATED : HttpStatus.OK)
							.cacheControl(noStore()).body(Map.of("success", true, "data", toProfileDto(result))));
		});
	}

	// API04
	@GetMapping("/profiles/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable UUID id, ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> profiles.get(actor, id))
				.map(profile -> envelope(profile));
	}

	// API05
	@PatchMapping("/profiles/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> update(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			UpdateRequest request = DigitalHumanOperations.parseStrict(body, UpdateRequest.class);
			if (request.requestId() == null) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "requestId 必填。"));
			}
			ProfileInput input = DigitalHumanProfileService.sanitize(request.toInput());
			return profiles.update(actor, id, input, request.expectedVersion(), request.requestId())
					.map(row -> envelope(row));
		});
	}

	// API06：DELETE 带 JSON requestId（fetch 支持），200 {id,status:'deleted'}。
	@DeleteMapping("/profiles/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> delete(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			DeleteRequest request = DigitalHumanOperations.parseStrict(body, DeleteRequest.class);
			if (request.requestId() == null) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "requestId 必填。"));
			}
			return profiles.delete(actor, id, request.requestId())
					.map(result -> envelope(Map.of("id", result.id().toString(), "status", "deleted")));
		});
	}

	private static Profile toProfileDto(CreateResult result) {
		return new Profile(result.profile().id(), result.profile().name(), result.revision().persona(),
				result.revision().greeting(), result.revision().tone(), result.revision().avatarId(),
				result.revision().voiceId(), result.revision().catalogVersion(), result.profile().version(),
				result.profile().status(), result.profile().createdAt(), result.profile().updatedAt());
	}

	private static ResponseEntity<Map<String, Object>> envelope(Object data) {
		return ResponseEntity.ok().cacheControl(noStore()).body(Map.of("success", true, "data", data));
	}

	private static CacheControl noStore() {
		return CacheControl.noStore();
	}

	/** API03 请求：ProfileInput 全字段 + requestId（一次严格解码，未知字段拒绝）。 */
	public record CreateRequest(String name, String persona, String greeting, DigitalHumanRecords.Tone tone,
			String avatarId, String voiceId, int catalogVersion, UUID requestId) {

		ProfileInput toInput() {
			return new ProfileInput(name, persona, greeting, tone, avatarId, voiceId, catalogVersion);
		}
	}

	/** API05 请求：ProfileInput + expectedVersion + requestId。 */
	public record UpdateRequest(String name, String persona, String greeting, DigitalHumanRecords.Tone tone,
			String avatarId, String voiceId, int catalogVersion, Integer expectedVersion, UUID requestId) {

		ProfileInput toInput() {
			return new ProfileInput(name, persona, greeting, tone, avatarId, voiceId, catalogVersion);
		}
	}

	public record DeleteRequest(UUID requestId) {
	}
}
