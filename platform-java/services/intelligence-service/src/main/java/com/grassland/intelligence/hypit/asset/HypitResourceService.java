package com.grassland.intelligence.hypit.asset;

import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.Map;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 资源字节面（任务书 #107-1 C107-05 / K09）：上传固化与 Range 代理。
 *
 * <p>
 * 内部通道固定 baseURL + Bearer；本服务无全局 WebClient.Builder bean（全仓惯例自建）。 500MiB
 * 上限在入口先拒（E02：超限立即停止，不落临时文件）；Range 语义由 sidecar 的 resource
 * 层实现，本类只透传状态与字节——206/416 判定不在此重复。
 */
@Service
public class HypitResourceService {

	/** E02/K07：单素材上限 500MiB，超出立即拒绝。 */
	static final long MAX_UPLOAD_BYTES = 500L * 1024 * 1024;

	private final HypitProperties properties;
	private final WebClient webClient;

	public HypitResourceService(HypitProperties properties) {
		this.properties = properties;
		this.webClient = WebClient.builder().baseUrl(properties.sidecarBaseUrl()).build();
	}

	public record IngestReceipt(String handle, String sha256, long sizeBytes) {
	}

	public record ResourceResponse(int status, HttpHeaders headers, Flux<DataBuffer> body) {
	}

	private void requireConfigured(String what) {
		if (properties.internalToken().length() < 32) {
			throw new IntelligenceException(503, "hypit_backend_unavailable", what + "需要配置 sidecar 内部 token。");
		}
	}

	/** 流式固化：sidecar 写入受控资源根并登记句柄（Java 不落任何临时文件）。 */
	public Mono<IngestReceipt> ingest(Flux<DataBuffer> body, String fileName, String contentType, long expectedBytes) {
		requireConfigured("素材上传");
		// expectedBytes=-1 表示 multipart 流式长度未知：上限由 sidecar 边收边拒；
		// 已知长度超限才在入口先拒（E02：超限立即停止，不落临时文件）。
		if (expectedBytes > MAX_UPLOAD_BYTES) {
			return Mono.error(new IntelligenceException(413, "hypit_too_large", "素材超过 500MiB 上限，已停止接收。"));
		}
		return Mono
				.fromCallable(() -> webClient.post().uri("/internal/v1/resources")
						.header("Authorization", "Bearer " + properties.internalToken())
						.header("X-Hypit-File-Name", fileName).contentType(MediaType.parseMediaType(contentType))
						.body(org.springframework.web.reactive.function.BodyInserters
								.fromDataBuffers(body == null ? Flux.<DataBuffer>empty() : body))
						.retrieve().bodyToMono(String.class).block(Duration.ofSeconds(120)))
				.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).map(raw -> {
					Map<String, Object> parsed = HypitJson.read(raw);
					return new IngestReceipt(HypitJson.stringValue(parsed.get("handle"), ""),
							HypitJson.stringValue(parsed.get("sha256"), ""),
							HypitJson.longValue(parsed.get("sizeBytes"), 0));
				});
	}

	/** Range 透传代理：sidecar 决定 200/206/416，此处只搬运营状态与字节。 */
	public Mono<ResourceResponse> open(String handle, String rangeHeader) {
		requireConfigured("素材读取");
		return Mono.fromCallable(() -> {
			WebClient.RequestHeadersSpec<?> spec = webClient.get().uri("/internal/v1/resources/{handle}", handle)
					.header("Authorization", "Bearer " + properties.internalToken());
			if (rangeHeader != null && !rangeHeader.isBlank()) {
				spec = spec.header(HttpHeaders.RANGE, rangeHeader);
			}
			// toEntityFlux：响应体保持可流式订阅（exchangeToMono 回调返回后连接即被
			// 释放，事后 bodyToFlux 只能读到已排空的连接——206 头对体空的实录）。
			// onStatus 映射空 Mono：416/5xx 也是合法透传状态，不触发 retrieve 默认抛错。
			org.springframework.http.ResponseEntity<Flux<DataBuffer>> entity = spec.retrieve()
					.onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
							response -> Mono.empty())
					.toEntityFlux(DataBuffer.class).block(Duration.ofSeconds(30));
			if (entity == null) {
				throw new IntelligenceException(503, "hypit_backend_unavailable", "资源服务无响应。");
			}
			return new ResourceResponse(entity.getStatusCode().value(), entity.getHeaders(), entity.getBody());
		}).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
	}
}
