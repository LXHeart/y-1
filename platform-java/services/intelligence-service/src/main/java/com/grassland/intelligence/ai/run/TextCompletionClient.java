package com.grassland.intelligence.ai.run;

import com.grassland.intelligence.ai.ChatChunk;
import com.grassland.intelligence.ai.PinnedByokClients;
import com.grassland.intelligence.ai.DnsPinningResolver;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ThinkingContentFilter;
import com.grassland.intelligence.ai.controlplane.PlatformProviderPolicy;
import com.grassland.intelligence.ai.run.dialect.TextDialect;
import com.grassland.intelligence.ai.run.dialect.TextDialects;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
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
 * Text completion 客户端（GL-P3-AI-001 控制面执行链路）。
 *
 * <p>
 * 平台 run 与 BYOK run 共用：平台传平台凭据解密后的 key 作 bearer，BYOK 传用户 key 作 bearer。
 *
 * <p>
 * <b>协议形状按 provider 分方言</b>（{@link TextDialects}）：路径、鉴权头、请求体、响应/流解析由
 * {@link TextDialect} 决定，本类只负责传输层——DNS pinning、SSRF 受信校验、超时、响应体上限、
 * 错误码映射、{@code <think>} 剥离与 SSE 归一化。未知/legacy provider 回落 OpenAI Chat
 * Completions 方言，与分方言之前的行为逐字节一致。
 *
 * <p>
 * 平台地址执行受信 origin 校验；BYOK 地址只允许 HTTPS，并在保存和执行时验证全部公网 DNS 结果。 实际 Netty
 * 连接使用固定地址解析器，避免校验后再次解析形成 DNS rebinding 窗口。
 */
@Component
public class TextCompletionClient {

	private static final Logger logger = LoggerFactory.getLogger(TextCompletionClient.class);

	private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

	private final Duration timeout;
	private final DnsPinningResolver dnsPinning;
	private final PlatformProviderPolicy platformProviderPolicy;
	private final TextDialects dialects;

	public TextCompletionClient(@Value("${ai.platform-model.read-timeout:PT120S}") Duration readTimeout,
			DnsPinningResolver dnsPinning, PlatformProviderPolicy platformProviderPolicy, TextDialects dialects) {
		this.timeout = readTimeout;
		this.dnsPinning = dnsPinning;
		this.platformProviderPolicy = platformProviderPolicy;
		this.dialects = dialects;
	}

	public Mono<TextCompletionResult> complete(String provider, String baseUrl, String bearer, String model,
			String prompt, int maxTokens, boolean byok) {
		return completeMessages(provider, baseUrl, bearer, model, List.of(ChatMessage.user(prompt)), maxTokens, byok);
	}

	public Mono<TextCompletionResult> completeMessages(String provider, String baseUrl, String bearer, String model,
			List<ChatMessage> messages, int maxTokens, boolean byok) {
		return completeMessages(provider, baseUrl, bearer, model, messages, maxTokens, byok, null);
	}

	/**
	 * 带超时覆写的完成调用（视频分析等多模态长任务需要超出默认读超时的窗口）。 覆写值同时作用于连接层 responseTimeout 与整体 Mono
	 * timeout；null 回落默认。
	 */
	public Mono<TextCompletionResult> completeMessages(String provider, String baseUrl, String bearer, String model,
			List<ChatMessage> messages, int maxTokens, boolean byok, Duration timeoutOverride) {
		Duration effectiveTimeout = timeoutOverride == null ? this.timeout : timeoutOverride;
		TextDialect dialect = dialects.resolve(provider);

		// 请求体在 fromCallable 内构造：方言可能对不支持的输入抛（如 Anthropic/Responses 收到 Video →
		// 400），装配期抛会变成同步异常而绕过下游 onErrorMap，必须留在订阅期。
		return Mono.fromCallable(() -> new Attempt(
				byok ? pinnedByokClient(baseUrl, effectiveTimeout) : platformClient(dialect, baseUrl, effectiveTimeout),
				dialect.body(model, messages, maxTokens, false))).subscribeOn(Schedulers.boundedElastic())
				.flatMap(attempt -> attempt.client().post().uri(dialect.path(model, false))
						.contentType(MediaType.APPLICATION_JSON).headers(headers -> dialect.applyAuth(headers, bearer))
						.bodyValue(attempt.body()).exchangeToMono(response -> {
							int status = response.statusCode().value();
							if (status >= 200 && status < 300) {
								// 上游 200 但响应体不可解析（如 MiniMax 把错误包在 base_resp 里返回 HTTP 200）也要留痕。
								return response.bodyToMono(String.class).flatMap(responseBody -> Mono
										.fromCallable(() -> dialect.parse(responseBody)).onErrorMap(e -> {
											logger.warn(
													"AI completion unparseable 200 response: dialect={} model={} error={} body={}",
													dialect.name(), model, e.getMessage(), snippet(responseBody));
											return e instanceof IntelligenceException ie
													? ie
													: new IntelligenceException(502, "AI provider 返回了无法解析的内容");
										}));
							}
							return response.bodyToMono(String.class).defaultIfEmpty("")
									.doOnNext(errorBody -> logger.warn(
											"AI completion upstream failed: status={} dialect={} model={} body={}",
											status, dialect.name(), model, snippet(errorBody)))
									.flatMap(ignored -> Mono.error(new IntelligenceException(502, "AI provider 调用失败")));
						}))
				.timeout(effectiveTimeout)
				.onErrorMap(TimeoutException.class, e -> new IntelligenceException(504, "AI provider 调用超时"))
				.onErrorMap(e -> {
					if (e instanceof IntelligenceException ie) {
						return ie;
					}
					// 上游已应答的失败在其上方留痕（unparseable/upstream failed）；落到这里的
					// 是出站前/传输层失败（origin 不受信、DNS、连接拒绝等），2026-09-02 分镜
					// 静默 502 实录：此处不留痕则兜底文案无从排障。
					logger.warn("AI completion failed before upstream response: dialect={} model={} {}: {}",
							dialect.name(), model, e.getClass().getSimpleName(), e.getMessage());
					return new IntelligenceException(502, "AI provider 调用失败");
				});
	}

	/** 一次尝试所需的两样东西：钉扎后的客户端 + 方言构造出的请求体（都在订阅期产生）。 */
	private record Attempt(WebClient client, Map<String, Object> body) {
	}

	/**
	 * 流式完成调用（文章 outline/content 等逐 token SSE 场景）：按方言 POST 流式端点 → 按 {@code \n} 分行 →
	 * 剥 {@code data: } 前缀 → 遇方言终止标记停止 → 方言增量映射为 {@link ChatChunk}； malformed 行吞掉（流已
	 * 200 开头，无法再改状态码）。
	 *
	 * <p>
	 * Gemini 无终止哨兵（{@link TextDialect#isStreamEnd} 恒 false），流随连接自然结束——
	 * {@code takeWhile} 对它是恒真透传，不能因此改成「必须见到哨兵才算完」。
	 *
	 * <p>
	 * 超时语义：覆写值同时作为连接层 responseTimeout 与逐信号（chunk 间隔）超时。
	 */
	public Flux<ChatChunk> streamMessages(String provider, String baseUrl, String bearer, String model,
			List<ChatMessage> messages, int maxTokens, boolean byok, Duration timeoutOverride) {
		Duration effectiveTimeout = timeoutOverride == null ? this.timeout : timeoutOverride;
		TextDialect dialect = dialects.resolve(provider);

		return Flux.defer(() -> {
			// <think> 剥离器必须 per-subscription：标签可能被 token 边界切开，跨 chunk 状态不可共享。
			ThinkingContentFilter.Stream thinker = new ThinkingContentFilter.Stream();
			// 同非流式：方言构造请求体可能抛，留在订阅期。
			return Mono
					.fromCallable(() -> new Attempt(
							byok
									? pinnedByokClient(baseUrl, effectiveTimeout)
									: platformClient(dialect, baseUrl, effectiveTimeout),
							dialect.body(model, messages, maxTokens, true)))
					.subscribeOn(Schedulers.boundedElastic())
					.flatMapMany(attempt -> attempt.client().post().uri(dialect.path(model, true))
							.contentType(MediaType.APPLICATION_JSON)
							.headers(headers -> dialect.applyAuth(headers, bearer)).bodyValue(attempt.body()).retrieve()
							.onStatus(status -> status.is4xxClientError(),
									r -> Mono.error(new IntelligenceException(400, "AI 上游拒绝请求")))
							.onStatus(status -> status.is5xxServerError(),
									r -> Mono.error(new IntelligenceException(502, "AI 上游暂不可用")))
							.bodyToFlux(String.class))
					.map(String::trim)
					// WebClient 对 text/event-stream 返回的元素已是 data 值（SSE reader 剥掉前缀，
					// [DONE] 也无前缀）；text/plain 等原始行式则带 "data: " 前缀——此处归一化两种形态。
					.map(line -> line.startsWith("data: ") ? line.substring("data: ".length()).trim() : line)
					.takeWhile(line -> !dialect.isStreamEnd(line)).mapNotNull(dialect::streamDelta).flatMap(delta -> {
						String visible = thinker.feed(delta);
						return visible.isEmpty() ? Mono.<ChatChunk>empty() : Mono.just(new ChatChunk(visible));
					})
					// 流尾释放被疑似标签前缀扣住的正文残余；思考态残余（截断）丢弃。
					.concatWith(Mono.fromSupplier(() -> thinker.flush()).filter(s -> !s.isEmpty()).map(ChatChunk::new))
					.timeout(effectiveTimeout)
					.onErrorMap(TimeoutException.class, e -> new IntelligenceException(504, "AI provider 调用超时"));
		});
	}

	/**
	 * 计量流（任务书 #105D C105D-03 / 共享契约 K08）：首期仅批准 openai-completions（qwen /
	 * openai-compatible 均解析到该方言）——请求体追加
	 * {@code stream_options.include_usage=true}，合法 usage 终帧（{@code choices:[]} +
	 * usage，[DONE] 之前到达）映射为精确 usage 事件；[DONE] 不吞前帧 usage；终止标记后才发 done，半流中断无
	 * done（调用方据此落 pending，不重发）。
	 *
	 * <p>
	 * 旧 {@link #streamMessages} 返回 {@link ChatChunk}、不携带计量——签名与行为保持不变。
	 */
	public Flux<TextStreamEvent> streamMeteredMessages(String provider, String baseUrl, String bearer, String model,
			List<ChatMessage> messages, int maxTokens, boolean byok, Duration timeoutOverride) {
		Duration effectiveTimeout = timeoutOverride == null ? this.timeout : timeoutOverride;
		TextDialect dialect = dialects.resolve(provider);
		if (!"openai-completions".equals(dialect.name())) {
			return Flux.error(new IntelligenceException(409, "dh_configuration_changed", "该 provider 暂不支持数字人计量流。"));
		}
		return Flux.defer(() -> {
			ThinkingContentFilter.Stream thinker = new ThinkingContentFilter.Stream();
			java.util.concurrent.atomic.AtomicReference<CapturedUsage> usage = new java.util.concurrent.atomic.AtomicReference<>();
			java.util.concurrent.atomic.AtomicReference<String> runId = new java.util.concurrent.atomic.AtomicReference<>();
			java.util.concurrent.atomic.AtomicBoolean cleanEnd = new java.util.concurrent.atomic.AtomicBoolean(false);
			return Mono
					.fromCallable(() -> new Attempt(
							byok
									? pinnedByokClient(baseUrl, effectiveTimeout)
									: platformClient(dialect, baseUrl, effectiveTimeout),
							meteredBody(dialect, model, messages, maxTokens)))
					.subscribeOn(Schedulers.boundedElastic())
					.flatMapMany(attempt -> attempt.client().post().uri(dialect.path(model, true))
							.contentType(MediaType.APPLICATION_JSON)
							.headers(headers -> dialect.applyAuth(headers, bearer)).bodyValue(attempt.body()).retrieve()
							.onStatus(status -> status.is4xxClientError(),
									r -> Mono.error(new IntelligenceException(400, "AI 上游拒绝请求")))
							.onStatus(status -> status.is5xxServerError(),
									r -> Mono.error(new IntelligenceException(502, "AI 上游暂不可用")))
							.bodyToFlux(String.class))
					.map(String::trim)
					// WebClient 对 text/event-stream 返回的元素已是 data 值（SSE reader 剥前缀）；
					// text/plain 等原始行式带 "data: " 前缀——归一化两种形态（与 streamMessages 同口径）。
					.map(line -> line.startsWith("data: ") ? line.substring("data: ".length()).trim() : line)
					.takeWhile(line -> {
						if (dialect.isStreamEnd(line)) {
							cleanEnd.set(true);
							return false;
						}
						return true;
					}).mapNotNull(line -> meteredFrame(line, dialect, thinker, usage, runId))
					.concatWith(Flux.defer(() -> {
						java.util.List<TextStreamEvent> tail = new java.util.ArrayList<>();
						String flushed = thinker.flush();
						if (!flushed.isEmpty()) {
							tail.add(TextStreamEvent.delta(flushed));
						}
						CapturedUsage captured = usage.get();
						if (captured != null) {
							tail.add(TextStreamEvent.usage(runId.get(), captured.inputTokens(),
									captured.outputTokens()));
						}
						if (cleanEnd.get()) {
							tail.add(TextStreamEvent.done());
						}
						return Flux.fromIterable(tail);
					})).timeout(effectiveTimeout)
					.onErrorMap(TimeoutException.class, e -> new IntelligenceException(504, "AI provider 调用超时"));
		});
	}

	/** 方言请求体 + stream_options.include_usage（不改动方言既有 body 语义）。 */
	private static java.util.Map<String, Object> meteredBody(TextDialect dialect, String model,
			List<ChatMessage> messages, int maxTokens) {
		java.util.Map<String, Object> body = new java.util.LinkedHashMap<>(
				dialect.body(model, messages, maxTokens, true));
		body.put("stream_options", java.util.Map.of("include_usage", true));
		return body;
	}

	private record CapturedUsage(long inputTokens, long outputTokens) {
	}

	/**
	 * 单帧 → 事件：usage 帧捕获（choices=[] 合法、负数/缺失视为无效不捕获）；其余经方言 delta + 思考过滤。坏 JSON
	 * 静默跳过（流已 200 开头），与旧流口径一致。
	 */
	private static TextStreamEvent meteredFrame(String line, TextDialect dialect, ThinkingContentFilter.Stream thinker,
			java.util.concurrent.atomic.AtomicReference<CapturedUsage> usage,
			java.util.concurrent.atomic.AtomicReference<String> runId) {
		com.fasterxml.jackson.databind.JsonNode root;
		try {
			root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(line);
		} catch (Exception malformed) {
			return null;
		}
		if (root.path("id").isTextual() && runId.get() == null) {
			runId.set(root.path("id").asText());
		}
		com.fasterxml.jackson.databind.JsonNode usageNode = root.path("usage");
		if (usageNode.isObject()) {
			long input = usageNode.path("prompt_tokens").asLong(Long.MIN_VALUE);
			long output = usageNode.path("completion_tokens").asLong(Long.MIN_VALUE);
			if (input >= 0 && output >= 0 && usage.get() == null) {
				usage.set(new CapturedUsage(input, output));
			}
			return null;
		}
		String delta = dialect.streamDelta(line);
		if (delta == null) {
			return null;
		}
		String visible = thinker.feed(delta);
		return visible.isEmpty() ? null : TextStreamEvent.delta(visible);
	}

	/** 上游错误体摘要（压缩空白、截断防刷屏）。 */
	private static String snippet(String body) {
		String compact = body == null ? "" : body.replaceAll("\\s+", " ").trim();
		return compact.length() > 300 ? compact.substring(0, 300) + "…" : compact;
	}

	/**
	 * 平台目的地必须在受信 origin 表内（表不区分 provider——信任对象是目的地，与方言无关）。
	 *
	 * <p>
	 * 分方言前这里写死 {@code validateBaseUrl()}（= provider 名固定 qwen）再 catch 一次
	 * openai-compatible 双探，效果是<b>运行期从不校验 provider 名</b>、只校验目的地。这里传
	 * <b>已解析方言</b>的名字而非原始 provider 串，正是为了保住这一性质：名字不认识的存量行 由 {@link TextDialects}
	 * 回落默认方言、照旧可跑，而 origin 白名单一步不让。 provider 名的取值约束在控制面写入路径（DTO 正则）上把，不在出站路径上重复把。
	 */
	private WebClient platformClient(TextDialect dialect, String baseUrl, Duration responseTimeout) {
		platformProviderPolicy.validate(dialect.name(), baseUrl);
		// GL-P3-AI-001 尾巴：平台路径同样固定连接地址（env 固定表优先，否则创建时解析一次）
		return com.grassland.intelligence.ai.OpenAiCompatibleHttpClientFactory.pinnedPlatformClient(
				TextCompletionClient.class, baseUrl, dnsPinning, responseTimeout, MAX_RESPONSE_BYTES);
	}

	private WebClient pinnedByokClient(String baseUrl, Duration responseTimeout) {
		return PinnedByokClients.forBaseUrl(baseUrl, dnsPinning, responseTimeout, MAX_RESPONSE_BYTES);
	}
}
