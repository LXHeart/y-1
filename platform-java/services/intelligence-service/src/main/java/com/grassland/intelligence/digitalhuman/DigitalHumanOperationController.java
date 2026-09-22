package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationDto;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 操作回执端点（任务书 #105B C105B-03 / K03 API39）：归属校验的只读 operation——owner 授权，
 * kind/state/resultRef/errorCode，无正文。非本人/不存在统一 404（不泄露存在性）。
 */
@RestController
public class DigitalHumanOperationController {

	private final DigitalHumanAuthorization authorization;
	private final DigitalHumanOperations operations;

	public DigitalHumanOperationController(DigitalHumanAuthorization authorization, DigitalHumanOperations operations) {
		this.authorization = authorization;
		this.operations = operations;
	}

	@GetMapping("/api/digital-human/operations/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable UUID id, ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest())
				.flatMap(actor -> operations.read(actor, id).map(operation -> ResponseEntity.ok()
						.cacheControl(CacheControl.noStore()).body(Map.of("success", true, "data", toDto(operation)))));
	}

	private static OperationDto toDto(DigitalHumanRecords.OperationRow row) {
		return new OperationDto(row.id(), row.kind().name(), row.state(), row.resourceId(), row.resultRef(),
				row.errorCode(), row.createdAt(), row.updatedAt());
	}
}
