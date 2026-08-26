package com.grassland.intelligence.ai.run;

import com.grassland.http.ManagedWebClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.ChatChunk;
import com.grassland.intelligence.ai.PinnedByokClients;
import com.grassland.intelligence.ai.DnsPinningResolver;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.controlplane.PlatformProviderPolicy;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * OpenAI 兼容 text completion 客户端（GL-P3-AI-001 控制面执行链路）。
 *
 * <p>
 * 平台 run 与 BYOK run 共用：平台传 env 平台 api-key 作 bearer，BYOK 传解密后的明文 key 作 bearer。
 * 解析 {@code choices[0].message.content} +
 * {@code usage.prompt_tokens/completion_tokens} 供结算计量。
 *
 * <p>
 * 平台地址执行基础 URL 校验；BYOK 地址只允许 HTTPS，并在保存和执行时验证全部公网 DNS 结果。 实际 Netty
 * 连接使用固定地址解析器，避免校验后再次解析形成 DNS rebinding 窗口。
 */
@Component
public class TextCompletionClient {

	private static final Logger logger = LoggerFactory.getLogger(TextCompletionClient.class);

	private final ObjectMapper mapper = new ObjectMapper();
	private final Duration timeout;
	private final DnsPinningResolver dnsPinning;
	private final PlatformProviderPolicy platformProviderPolicy;

	public TextCompletionClient(@Value("${ai.qwen.read-timeout-ms:120000}") long readTimeoutMs,
			DnsPinningResolver dnsPinning, PlatformProviderPolicy platformProviderPolicy) {
		this.timeout = Duration.ofMillis(readTimeoutMs);
		this.dnsPinning = dnsPinning;
		this.platformProviderPolicy = platformProviderPolicy;
	}

	public Mono<TextCompletionResult> complete(String baseUrl, String bearer, String model, String prompt,
			int maxTokens, boolean byok) {
		return completeMessages(baseUrl, bearer, model, List.of(ChatMessage.user(prompt)), maxTokens, byok);
	}

	public Mono<TextCompletionResult> completeMessages(String baseUrl, String bearer, String model,
			List<ChatMessage> messages, int maxTokens, boolean byok) {
		return completeMessages(baseUrl, bearer, model, messages, maxTokens, byok, null);
	}

	/**
	 * 带超时覆写的完成调用（视频分析等多模态长任务需要超出默认读超时的窗口）。 覆写值同时作用于连接层 responseTimeout 与整体 Mono
	 * timeout；null 回落默认。
	 */
	public Mono<TextCompletionResult> completeMessages(String baseUrl, String bearer, String model,
			List<ChatMessage> messages, int maxTokens, boolean byok, Duration timeoutOverride) {
		Duration effectiveTimeout = timeoutOverride == null ? this.timeout : timeoutOverride;
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", model);
		body.put("messages", messages.stream().map(TextCompletionClient::messageBody).toList());
		body.put("stream", false);
		body.put("max_tokens", maxTokens);
		body.put("enable_thinking", false);

		return Mono.fromCallable(
				() -> byok ? pinnedByokClient(baseUrl, effectiveTimeout) : platformClient(baseUrl, effectiveTimeout))
				.subscribeOn(Schedulers.boundedElastic())
				.flatMap(client -> client.post().uri("chat/completions").contentType(MediaType.APPLICATION_JSON)
						.header("Authorization", "Bearer " + bearer).bodyValue(body).exchangeToMono(response -> {
							int status = response.statusCode().value();
							if (status >= 200 && status < 300) {
								// 上游 200 但响应体不可解析（如 MiniMax 把错误包在 base_resp 里返回 HTTP 200）也要留痕。
								return response.bodyToMono(String.class).flatMap(
										responseBody -> Mono.fromCallable(() -> parse(responseBody)).onErrorMap(e -> {
											logger.warn("AI completion unparseable 200 response: model={} error={} body={}",
													model, e.getMessage(), snippet(responseBody));
											return e instanceof IntelligenceException ie ? ie
													: new IntelligenceException(502, "AI provider 返回了无法解析的内容");
										}));
							}
							return response.bodyToMono(String.class).defaultIfEmpty("")
									.doOnNext(errorBody -> logger.warn(
											"AI completion upstream failed: status={} model={} body={}",
											status, model, snippet(errorBody)))
									.flatMap(ignored -> Mono.error(new IntelligenceException(502, "AI provider 调用失败")));
						}))
				.timeout(effectiveTimeout)
				.onErrorMap(TimeoutException.class, e -> new IntelligenceException(504, "AI provider 调用超时"))
				.onErrorMap(e -> e instanceof IntelligenceException
						? e
						: new IntelligenceException(502, "AI provider 调用失败"));
	}

	/**
	 * 流式完成调用（文章 outline/content 等逐 token SSE 场景）：POST /chat/completions
	 * {@code stream:true} → 按 {@code \n} 分行 → 剥 {@code data: } 前缀 → 遇 {@code [DONE]} 终止 →
	 * {@code choices[0].delta.content} 映射为 {@link ChatChunk}；malformed 行吞掉。
	 * 超时语义：覆写值同时作为连接层 responseTimeout 与逐信号（chunk 间隔）超时。
	 */
	public Flux<ChatChunk> streamMessages(String baseUrl, String bearer, String model,
			List<ChatMessage> messages, int maxTokens, boolean byok, Duration timeoutOverride) {
		Duration effectiveTimeout = timeoutOverride == null ? this.timeout : timeoutOverride;
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", model);
		body.put("messages", messages.stream().map(TextCompletionClient::messageBody).toList());
		body.put("stream", true);
		body.put("max_tokens", maxTokens);
		body.put("enable_thinking", false);

		return Mono.fromCallable(
				() -> byok ? pinnedByokClient(baseUrl, effectiveTimeout) : platformClient(baseUrl, effectiveTimeout))
				.subscribeOn(Schedulers.boundedElastic())
				.flatMapMany(client -> client.post().uri("chat/completions").contentType(MediaType.APPLICATION_JSON)
						.header("Authorization", "Bearer " + bearer).bodyValue(body)
						.retrieve()
						.onStatus(status -> status.is4xxClientError(),
								r -> Mono.error(new IntelligenceException(400, "AI 上游拒绝请求")))
						.onStatus(status -> status.is5xxServerError(),
								r -> Mono.error(new IntelligenceException(502, "AI 上游暂不可用")))
						.bodyToFlux(String.class))
				.map(String::trim)
				.filter(line -> line.startsWith("data: "))
				.map(line -> line.substring("data: ".length()).trim())
				.takeWhile(line -> !"[DONE]".equals(line))
				.mapNotNull(this::extractDeltaContent)
				.timeout(effectiveTimeout)
				.onErrorMap(TimeoutException.class, e -> new IntelligenceException(504, "AI provider 调用超时"));
	}

	/** 上游错误体摘要（压缩空白、截断防刷屏）。 */
	private static String snippet(String body) {
		String compact = body == null ? "" : body.replaceAll("\\s+", " ").trim();
		return compact.length() > 300 ? compact.substring(0, 300) + "…" : compact;
	}

	/** 单条 SSE data 载荷 → 增量 chunk；非文本/空 delta/malformed JSON 一律吞掉（镜像 legacy）。 */
	private ChatChunk extractDeltaContent(String json) {
		try {
			JsonNode content = mapper.readTree(json).path("choices").path(0).path("delta").path("content");
			if (content.isTextual() && !content.asText().isEmpty()) {
				return new ChatChunk(content.asText());
			}
			return null;
		} catch (Exception e) {
			return null;
		}
	}

	private static Map<String, Object> messageBody(ChatMessage message) {		Map<String, Object> body = new LinkedHashMap<>();
		body.put("role", message.role());
		body.put("content",
				message.multimodal()
						? message.parts().stream().map(TextCompletionClient::partBody).toList()
						: message.content());
		return body;
	}

	private static Map<String, Object> partBody(ContentPart part) {
		return switch (part) {
			case ContentPart.Text text -> Map.of("type", "text", "text", text.text());
			case ContentPart.Image image -> Map.of("type", "image_url", "image_url", Map.of("url", image.url()));
			case ContentPart.Video video -> Map.of("type", "video_url", "video_url", Map.of("url", video.url()));
		};
	}

	private WebClient platformClient(String baseUrl, Duration responseTimeout) {
		// 平台解析的 baseUrl 可能来自 qwen 系（env 锚点）或 openai-compatible（控制面凭据/受信
		// origin 表）——两张 curated 白名单任一命中即放行；都不在才拒，SSRF 防护口径不放松。
		try {
			platformProviderPolicy.validateBaseUrl(baseUrl);
		} catch (IllegalArgumentException qwenRejected) {
			platformProviderPolicy.validate("openai-compatible", baseUrl);
		}
		// GL-P3-AI-001 尾巴：平台路径同样固定连接地址（env 固定表优先，否则创建时解析一次）
		return com.grassland.intelligence.ai.OpenAiCompatibleHttpClientFactory.pinnedPlatformClient(
				TextCompletionClient.class, baseUrl, dnsPinning, responseTimeout, 2 * 1024 * 1024);
	}

	private WebClient pinnedByokClient(String baseUrl, Duration responseTimeout) {
		return PinnedByokClients.forBaseUrl(baseUrl, dnsPinning, responseTimeout, 2 * 1024 * 1024);
	}

	private TextCompletionResult parse(String json) {
		try {
			JsonNode root = mapper.readTree(json);
			JsonNode choices = root.path("choices");
			String content = choices.isArray() && choices.size() > 0
					? choices.get(0).path("message").path("content").asText("")
					: "";
			JsonNode usage = root.path("usage");
			if (!usage.isObject()) {
				throw new IntelligenceException(502, "AI provider 缺少 usage");
			}
			int inputTokens = requireUsageInt(usage, "prompt_tokens");
			int outputTokens = requireUsageInt(usage, "completion_tokens");
			Math.addExact(inputTokens, outputTokens);
			String providerRunId = root.path("id").isTextual() ? root.path("id").asText() : null;
			return new TextCompletionResult(content, inputTokens, outputTokens, providerRunId);
		} catch (IntelligenceException e) {
			throw e;
		} catch (Exception e) {
			throw new IntelligenceException(502, "AI provider 返回了无法解析的内容");
		}
	}

	private static int requireUsageInt(JsonNode usage, String fieldName) {
		JsonNode value = usage.get(fieldName);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
			throw new IntelligenceException(502, "AI provider usage 无效");
		}
		int parsed = value.intValue();
		if (parsed < 0) {
			throw new IntelligenceException(502, "AI provider usage 无效");
		}
		return parsed;
	}

	private static String withTrailingSlash(String url) {
		return url.endsWith("/") ? url : url + "/";
	}
}
