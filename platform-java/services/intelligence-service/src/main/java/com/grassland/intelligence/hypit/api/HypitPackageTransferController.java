package com.grassland.intelligence.hypit.api;

import com.grassland.intelligence.hypit.template.HypitPackageTransferService;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 工程包浏览器传输面（任务书 107-fix-2 C107F2-30 / W184 NEW / §6.13、§8.3）：
 *
 * <ul>
 * <li>POST /api/hypit/imports：multipart form（requestId + file=ZIP + 可选 title），
 * 普通 owner 可上传自己的包；旧 JSON artifactRoot 415 明确纠错（不再把宿主路径当 上传源）。202
 * AcceptedJob——新工程 owner=调用方，进度经 export/import 查询面。</li>
 * <li>GET /api/hypit/exports/{exportId}：owner 绑定的导出状态 + 下载元数据
 * （downloadPath/sizeBytes/sha256/expiresAt），他人与不存在同答 404。</li>
 * <li>GET /api/hypit/exports/{exportId}/package：真实 application/zip 流 （Range
 * 206/416、ETag=sha256、Content-Disposition 安全文件名、24h 过期 410）， 字节经 DataBuffer
 * 直通不进内存。</li>
 * </ul>
 */
@RestController
public class HypitPackageTransferController {

	private final IntelligenceCallerResolver callers;
	private final HypitPackageTransferService transfers;

	public HypitPackageTransferController(IntelligenceCallerResolver callers, HypitPackageTransferService transfers) {
		this.callers = callers;
		this.transfers = transfers;
	}

	/** C107F2-30：multipart 上传导入（§6.13；requestId UUID、file 唯一、title ≤60 字）。 */
	@PostMapping(path = "/api/hypit/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> importUpload(
			@org.springframework.web.bind.annotation.RequestPart("requestId") String requestId,
			@org.springframework.web.bind.annotation.RequestPart("file") FilePart file,
			@org.springframework.web.bind.annotation.RequestPart(value = "title", required = false) String title,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(caller -> {
			final UUID request;
			try {
				request = UUID.fromString(requestId == null ? "" : requestId.trim());
			} catch (IllegalArgumentException error) {
				return Mono.error(new IntelligenceException(400, "hypit_invalid_input", "requestId 须为 uuid。"));
			}
			String filename = file.filename() == null ? "" : file.filename();
			if (!filename.toLowerCase().endsWith(".zip")) {
				return Mono.error(new IntelligenceException(415, "hypit_unsupported_media_type", "仅支持 ZIP 工程包（.zip）。"));
			}
			return transfers.uploadAndImport(caller.accountId(), request, file, title)
					.map(result -> ResponseEntity.status(HttpStatus.ACCEPTED)
							.cacheControl(org.springframework.http.CacheControl.noStore())
							.body(HypitDtos.success(Map.of("jobId", String.valueOf(result.get("jobId")), "projectId",
									String.valueOf(result.get("projectId")), "status",
									String.valueOf(result.getOrDefault("status", "succeeded"))))));
		});
	}

	/** 导出状态（owner 绑定）：status + download 元数据。 */
	@GetMapping("/api/hypit/exports/{exportId}")
	public Mono<ResponseEntity<Map<String, Object>>> exportStatus(@PathVariable String exportId,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(caller -> {
			UUID id = requireUuid(exportId);
			return transfers.exportStatus(caller.accountId(), id).map(body -> ResponseEntity.ok()
					.cacheControl(org.springframework.http.CacheControl.noStore()).body(HypitDtos.success(body)));
		});
	}

	/** 真实 ZIP 下载流：Range/ETag/Disposition 由 broker 产出，Java 透传字节。 */
	@GetMapping("/api/hypit/exports/{exportId}/package")
	public Mono<Void> downloadPackage(@PathVariable String exportId, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(caller -> {
			UUID id = requireUuid(exportId);
			String range = exchange.getRequest().getHeaders().getFirst("Range");
			return transfers.download(caller.accountId(), id, range).flatMap(stream -> {
				var response = exchange.getResponse();
				response.setStatusCode(org.springframework.http.HttpStatus.valueOf(stream.status()));
				for (Map.Entry<String, String> header : stream.headers().entrySet()) {
					response.getHeaders().set(header.getKey(), header.getValue());
				}
				Flux<DataBuffer> body = stream.body();
				if (exchange.getRequest().getMethod() == org.springframework.http.HttpMethod.HEAD) {
					return response.setComplete();
				}
				return response.writeWith(body);
			});
		});
	}

	private static UUID requireUuid(String value) {
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException error) {
			throw new IntelligenceException(400, "hypit_invalid_input", "id 须为 uuid。");
		}
	}
}
