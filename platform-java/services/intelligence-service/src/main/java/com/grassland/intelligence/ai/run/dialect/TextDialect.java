package com.grassland.intelligence.ai.run.dialect;

import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.run.TextCompletionResult;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;

/**
 * 文本补全的**协议方言**：把「同一次 text 调用」翻译成某一家上游的 HTTP 形状。
 *
 * <p>方言只负责四件事——相对路径、鉴权头、请求体、响应/流解析。传输层的一切
 * （DNS pinning、SSRF 受信校验、超时、响应体上限、错误码映射）留在
 * {@link com.grassland.intelligence.ai.run.TextCompletionClient}，方言实现不碰 WebClient，
 * 也不得自建出站客户端——否则会绕过 pinning 与 origin 白名单。
 *
 * <p><b>路径约定</b>：{@link #path} 返回的是相对 baseUrl 的路径，baseUrl 由平台凭据带**版本前缀**
 * （OpenAI 系 {@code .../v1}、Anthropic {@code https://api.anthropic.com/v1}、Google
 * {@code https://generativelanguage.googleapis.com/v1beta}）。客户端保证 baseUrl 以 {@code /} 结尾，
 * 所以此处一律返回不带前导 {@code /} 的相对段（前导斜杠会把版本前缀吃掉）。
 *
 * <p><b>无状态</b>：实现必须是无状态单例（多请求并发共享同一实例），逐订阅状态（如
 * {@code <think>} 剥离器）由客户端持有。
 */
public interface TextDialect {

	/** 方言标识，与平台凭据/模型行的 {@code provider} 列取值一致。 */
	String name();

	/**
	 * 相对请求路径。Google 把模型名与「是否流式」编进路径，故两个参数都要收。
	 *
	 * @param model  上游模型名
	 * @param stream 是否流式调用
	 */
	String path(String model, boolean stream);

	/** 写鉴权头。OpenAI 系是 {@code Authorization: Bearer}，Anthropic 是 {@code x-api-key}，Google 是 {@code x-goog-api-key}。 */
	void applyAuth(HttpHeaders headers, String bearer);

	/** 构造请求体。{@code stream=true} 时按各家开关打开增量输出。 */
	Map<String, Object> body(String model, List<ChatMessage> messages, int maxTokens, boolean stream);

	/**
	 * 解析非流式响应。
	 *
	 * <p>实现约定：正文取不到 → 空串（不抛）；<b>usage 缺失或不合法 → 抛</b>
	 * {@link com.grassland.intelligence.security.IntelligenceException}（502）——计量是结算依据，
	 * 静默填 0 会让平台侧免费跑走真实 token。
	 */
	TextCompletionResult parse(String json);

	/**
	 * 单条 SSE data 载荷 → 本次增量文本；非文本事件/空增量/坏 JSON 一律返回 {@code null} 跳过
	 * （流已 200 开头，无法再改状态码，坏帧只能吞）。
	 */
	String streamDelta(String data);

	/** 该 data 载荷是否为流终止标记（OpenAI {@code [DONE]}、Anthropic {@code message_stop} 等）。 */
	boolean isStreamEnd(String data);
}
