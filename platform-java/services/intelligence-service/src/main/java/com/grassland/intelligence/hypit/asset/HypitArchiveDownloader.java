package com.grassland.intelligence.hypit.asset;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * sidecar 内部资源端点下载器（/internal/v1/resources/{handle}，K04.5）： 内部 Bearer
 * token、字节预算上限、Range 不需要（归档取全量）。 token 与 handle 绝不进日志（只记录状态码）。
 */
public final class HypitArchiveDownloader {

	public record SidecarBytes(byte[] value, String mediaType, String handle) {
	}

	private static final Duration TIMEOUT = Duration.ofSeconds(60);

	private HypitArchiveDownloader() {
	}

	/** 全量下载一个资源句柄；sidecar 未配置/不可达/超预算时如实失败。 */
	public static Mono<SidecarBytes> download(String sidecarBaseUrl, String internalToken, String handle,
			long maxBytes) {
		if (internalToken == null || internalToken.length() < 32) {
			return Mono.error(new IllegalStateException("hypit internal token is not configured"));
		}
		WebClient client = WebClient.builder().baseUrl(sidecarBaseUrl).build();
		return client.get().uri("/internal/v1/resources/" + handle)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + internalToken).accept(MediaType.APPLICATION_OCTET_STREAM)
				.exchangeToMono(response -> {
					if (!response.statusCode().is2xxSuccessful()) {
						return Mono.error(new IllegalStateException(
								"resource download failed with HTTP " + response.statusCode().value()));
					}
					long declared = response.headers().contentLength().orElse(-1L);
					if (declared > maxBytes) {
						return Mono.error(new IllegalStateException("resource exceeds archive byte budget"));
					}
					MediaType type = response.headers().contentType().orElse(null);
					String mediaType = type == null ? "application/octet-stream" : type.toString();
					return response.bodyToMono(byte[].class).timeout(TIMEOUT).map(bytes -> {
						if (bytes.length > maxBytes) {
							throw new IllegalStateException("resource exceeds archive byte budget");
						}
						return new SidecarBytes(bytes, mediaType, handle);
					});
				});
	}
}
