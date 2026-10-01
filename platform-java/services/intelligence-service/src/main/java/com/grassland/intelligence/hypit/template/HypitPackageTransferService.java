package com.grassland.intelligence.hypit.template;

import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.job.HypitCommandRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.hypit.project.HypitProjectRepository;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 工程包浏览器传输（任务书 107-fix-2 C107F2-30 / W185 NEW / §6.13、§8.3）：
 * <ul>
 * <li>上传：multipart zip 流式转发到 broker 受控 transfer（transferId 由 requestId 派生、与
 * owner intent 绑定——Java 先验证调用者再发内部请求；边转发边计 sha256， 上传上限 4GiB 超限 413；随后
 * {@link HypitProjectPackageService#importTransfer} 完成 PG
 * 预留与收敛）。中断上传不留半包（broker staging 清理），无 ready 假工程。</li>
 * <li>下载：owner 校验 → 24h TTL → broker GET 内容为只读 ZIP 流，Range 头原样
 * 透传（206/416/ETag/Content-Disposition 由 broker 按 §6.13 产出），Java 侧 DataBuffer
 * 直通，不把字节读进内存。</li>
 * </ul>
 */
@Service
public class HypitPackageTransferService {

	/** 上传上限（§6.13：导入展开 ≤4GiB；上传 zip 同界）。 */
	private static final long MAX_UPLOAD_BYTES = 4L * 1024 * 1024 * 1024;
	private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(30);

	/** broker 流式回执：状态 + 需透传的头 + 字节流（DataBuffer 所有权交还写侧）。 */
	public record BrokerStream(int status, Map<String, String> headers, Flux<DataBuffer> body) {
	}

	private final HypitCommandRepository commands;
	private final HypitJobRepository jobs;
	private final HypitProjectRepository projects;
	private final HypitProjectPackageService packages;
	private final HypitProperties properties;
	private final WebClient webClient;

	public HypitPackageTransferService(HypitCommandRepository commands, HypitJobRepository jobs,
			HypitProjectRepository projects, HypitProjectPackageService packages, HypitProperties properties) {
		this.commands = commands;
		this.jobs = jobs;
		this.projects = projects;
		this.packages = packages;
		this.properties = properties;
		// 本服务无 WebClient.Builder bean（全仓惯例：各客户端自建），固定 baseURL。
		this.webClient = WebClient.builder().baseUrl(properties.sidecarBaseUrl()).build();
	}

	/**
	 * transferId 由 requestId 派生（SHA-256 截断成 uuid 形状）：同 requestId 稳定同键， 与 owner
	 * intent 绑定（服务端凭据请求头，不信任客户端自报）。
	 */
	public static UUID transferIdFor(UUID requestId) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(("hypit-transfer:" + requestId).getBytes(StandardCharsets.UTF_8));
			long hi = 0;
			long lo = 0;
			for (int index = 0; index < 8; index += 1) {
				hi = (hi << 8) | (digest[index] & 0xffL);
				lo = (lo << 8) | (digest[index + 8] & 0xffL);
			}
			return new UUID((hi & 0xffffffffffff0fffL) | 0x0000000000005000L,
					(lo & 0x3fffffffffffffffL) | 0x8000000000000000L);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	/** 上传并导入：流式 PUT（哈希随行）→ importTransfer（PG 预留/收敛同 C29）。 */
	public Mono<Map<String, Object>> uploadAndImport(String accountId, UUID requestId, FilePart file, String title) {
		UUID transferId = transferIdFor(requestId);
		return upload(transferId, file)
				.flatMap(sha256 -> packages.importTransfer(accountId, requestId, transferId.toString(), sha256, title));
	}

	/** 边转发边计 sha256；不把整包读进内存（DataBuffer 分块直通）。 */
	private Mono<String> upload(UUID transferId, FilePart file) {
		if (!properties.enabled()) {
			return Mono.error(new IntelligenceException(HttpStatus.SERVICE_UNAVAILABLE.value(), "hypit_disabled",
					"Hypit 引擎未启用。"));
		}
		MessageDigest sha;
		try {
			sha = MessageDigest.getInstance("SHA-256");
		} catch (Exception error) {
			return Mono.error(new IllegalStateException(error));
		}
		MessageDigest digest = sha;
		long[] total = {0};
		Flux<DataBuffer> body = file.content().doOnNext(buffer -> {
			total[0] += buffer.readableByteCount();
			if (total[0] > MAX_UPLOAD_BYTES) {
				throw new IntelligenceException(HttpStatus.PAYLOAD_TOO_LARGE.value(), "hypit_too_large",
						"上传包超过 4GiB 上限。");
			}
			// C107F2-37（缺陷 K）：read(byte[]) 推进读位置——不还原的话，下游
			// BodyInserters 看到的 readableByteCount=0，PUT 体恒为空包（node 落
			// 0 字节、导入 EOCD 必败）。算完摘要恢复读位置，同一 buffer 再转发。
			int readFrom = buffer.readPosition();
			byte[] bytes = new byte[buffer.readableByteCount()];
			buffer.read(bytes);
			buffer.readPosition(readFrom);
			digest.update(bytes);
		});
		return webClient.put().uri("/internal/v1/package-transfers/{id}/content", transferId.toString())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.internalToken())
				.contentType(MediaType.APPLICATION_OCTET_STREAM).body(BodyInserters.fromDataBuffers(body))
				.exchangeToMono(response -> {
					if (response.statusCode().is2xxSuccessful()) {
						return response.bodyToMono(Map.class).map(stored -> String.valueOf(stored.get("sha256")));
					}
					int status = response.statusCode().value();
					return response.bodyToMono(String.class).defaultIfEmpty("")
							.flatMap(text -> Mono.error(new IntelligenceException(status == 413 ? 413 : 503,
									status == 413 ? "hypit_too_large" : "hypit_backend_unavailable",
									"上传转发失败：" + status)));
				}).timeout(TRANSFER_TIMEOUT);
	}

	/** 导出状态与下载元数据（owner 绑定；他人/不存在同答 404 不泄漏存在性）。 */
	public Mono<Map<String, Object>> exportStatus(String accountId, UUID exportId) {
		return commands.findById(exportId)
				.filter(command -> accountId.equals(command.accountId()) && "project.export".equals(command.action()))
				.switchIfEmpty(Mono.error(notFound())).flatMap(command -> {
					Map<String, Object> body = new LinkedHashMap<>();
					body.put("exportId", exportId.toString());
					if (command.resultJson() != null && !command.resultJson().isBlank()) {
						Map<String, Object> stored = HypitJson.read(command.resultJson());
						body.put("status", String.valueOf(stored.getOrDefault("state", "succeeded")));
						Object download = stored.get("download");
						if (download instanceof Map<?, ?>) {
							body.put("download", download);
						}
						return Mono.just(body);
					}
					return jobs.findByCommandId(command.id()).map(job -> {
						body.put("status", job.state());
						return body;
					}).defaultIfEmpty(body);
				});
	}

	/**
	 * 下载流：owner + succeeded + 24h TTL 三闸；Range 透传给 broker，返回状态与
	 * 需透传的头（Content-Range/ETag/Content-Disposition），字节 DataBuffer 直通。
	 */
	public Mono<BrokerStream> download(String accountId, UUID exportId, String rangeHeader) {
		return commands.findById(exportId)
				.filter(command -> accountId.equals(command.accountId()) && "project.export".equals(command.action()))
				.switchIfEmpty(Mono.error(notFound())).flatMap(command -> {
					if (command.resultJson() == null || command.resultJson().isBlank()) {
						return Mono.error(new IntelligenceException(HttpStatus.CONFLICT.value(), "hypit_state_conflict",
								"导出尚未完成，稍后再试。"));
					}
					Map<String, Object> stored = HypitJson.read(command.resultJson());
					Object download = stored.get("download");
					if (!(download instanceof Map<?, ?>)) {
						return Mono.error(new IntelligenceException(HttpStatus.CONFLICT.value(), "hypit_state_conflict",
								"导出缺少可下载产物。"));
					}
					@SuppressWarnings("unchecked")
					Map<String, Object> meta = (Map<String, Object>) download;
					String expiresAt = String.valueOf(meta.get("expiresAt"));
					try {
						if (Instant.parse(expiresAt).isBefore(Instant.now())) {
							return Mono.error(new IntelligenceException(HttpStatus.GONE.value(), "hypit_export_expired",
									"下载已过期（24 小时）；可从完成 job 重新发起同 revision 导出。"));
						}
					} catch (RuntimeException parseError) {
						// 缺/坏 expiresAt 视作未过期（旧回执兼容）。
					}
					return Mono.just(stored);
				})
				.flatMap(stored -> webClient.get()
						.uri("/internal/v1/package-transfers/{id}/content", exportId.toString())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.internalToken()).headers(headers -> {
							if (rangeHeader != null && !rangeHeader.isBlank()) {
								headers.set("Range", rangeHeader);
							}
						})
						// toEntityFlux：状态/头与可延后消费的字节流同行返回（连接保持到
						// 流终止）——Java 全程 DataBuffer 直通，不整包进内存。
						.retrieve()
						.onStatus(status -> status.value() == 410,
								response -> Mono.error(new IntelligenceException(HttpStatus.GONE.value(),
										"hypit_export_expired", "下载已过期（24 小时）；可从完成 job 重新发起同 revision 导出。")))
						.onStatus(status -> status.value() >= 400,
								response -> Mono.error(new IntelligenceException(HttpStatus.BAD_GATEWAY.value(),
										"hypit_engine_error", "下载取流失败：" + response.statusCode().value())))
						.toEntityFlux(DataBuffer.class).map(entity -> {
							Map<String, String> passthrough = new LinkedHashMap<>();
							for (String name : new String[]{"Content-Type", "Content-Length", "Content-Range",
									"Accept-Ranges", "ETag", "Content-Disposition", "Cache-Control"}) {
								String value = entity.getHeaders().getFirst(name);
								if (value != null) {
									passthrough.put(name, value);
								}
							}
							return new BrokerStream(entity.getStatusCode().value(), passthrough, entity.getBody());
						}))
				.timeout(TRANSFER_TIMEOUT);
	}

	private static IntelligenceException notFound() {
		return new IntelligenceException(404, "hypit_not_found", "导出不存在或无权访问。");
	}
}
