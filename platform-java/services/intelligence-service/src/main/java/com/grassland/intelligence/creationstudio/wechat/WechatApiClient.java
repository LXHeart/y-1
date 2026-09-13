package com.grassland.intelligence.creationstudio.wechat;

import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-19：微信公众号 API 客户端（固定端点）。 TLS/超时走 WebClient 默认连接器配置（10s 连接 / 15s
 * 读）；错误脱敏——只回 code 与受控 message，凭据/令牌不进异常文本。
 */
@Component
public class WechatApiClient {

	private final WebClient client;

	public WechatApiClient(WechatProperties properties) {
		this.client = WebClient.builder().baseUrl(properties.apiBaseUrl()).build();
	}

	public record AccessToken(String accessToken, int expiresInSeconds) {
	}

	/** GET /cgi-bin/token（verify/草稿写入共用；40125 invalid appsecret 等按 code 透出）。 */
	public Mono<AccessToken> fetchAccessToken(String appId, String appSecret) {
		return client.get()
				.uri(uriBuilder -> uriBuilder.path("/cgi-bin/token").queryParam("grant_type", "client_credential")
						.queryParam("appid", appId).queryParam("secret", appSecret).build())
				.accept(MediaType.APPLICATION_JSON).retrieve()
				.onStatus(HttpStatusCode::isError,
						response -> response.bodyToMono(String.class).defaultIfEmpty("")
								.map(body -> new WechatApiException(safeCode(body), "微信接口调用失败")))
				.bodyToMono(Map.class).flatMap(body -> {
					Object token = body.get("access_token");
					Object errcode = body.get("errcode");
					if (token instanceof String value && !value.isBlank()) {
						int expiresIn = body.get("expires_in") instanceof Number number ? number.intValue() : 7200;
						return Mono.just(new AccessToken(value, expiresIn));
					}
					return Mono.error(
							new WechatApiException(String.valueOf(errcode == null ? "unknown" : errcode), "微信凭据校验未通过"));
				}).timeout(Duration.ofSeconds(15));
	}

	static String safeCode(String body) {
		if (body == null) {
			return "network";
		}
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"errcode\"\\s*:\\s*(\\d+)").matcher(body);
		return matcher.find() ? matcher.group(1) : "network";
	}

	static class WechatApiException extends RuntimeException {

		private final String code;

		WechatApiException(String code, String message) {
			super(message);
			this.code = code;
		}

		public String code() {
			return code;
		}
	}
}
