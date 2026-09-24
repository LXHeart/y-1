package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.run.AiExecutionService.ExecutionContext;
import com.grassland.intelligence.ai.run.TextCompletionClient;
import com.grassland.intelligence.ai.run.TextStreamEvent;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.ProfileRevisionRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanTextPolicy.SpeechTextSegment;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 文本桥（任务书 #105D C105D-03 / 共享契约 K08、K08.1）：LLM 上下文组装、计量流调用与 TTS 前已审句段。
 *
 * <p>
 * 调用<b>冻结</b> text provider（ExecutionContext 携带，不重新路由）；上下文 = 冻结人设 system + 最近
 * 10 个<b>完整</b>对话对 + 当前输入，序列化后 ≤64KiB——超预算从<b>最老整对</b>裁剪，不自动总结；system+当前 输入仍超 →
 * 派发前拒绝。输出累计 >8000 码点即停止（上游按中断处理，不完整回答不进历史）。greeting 不走 LLM（固定句，C105D-04 TTS
 * 桥直读）。
 */
@Component
public class DigitalHumanTextBridge {

	/** K08：上下文最多 10 个完整对话对。 */
	public static final int MAX_CONTEXT_PAIRS = 10;
	/** K08：组装后 messages 序列化 UTF-8 上界 64KiB。 */
	public static final int MAX_CONTEXT_UTF8_BYTES = 65536;
	/** K08：单轮输出 8000 码点即停止。 */
	public static final int MAX_OUTPUT_CODE_POINTS = 8000;

	private final TextCompletionClient textClient;
	private final DigitalHumanTextPolicy policy;

	public DigitalHumanTextBridge(TextCompletionClient textClient, DigitalHumanTextPolicy policy) {
		this.textClient = textClient;
		this.policy = policy;
	}

	/** K07.1 CompletedPair：仅完整（双方非空）对话对才可进入历史；不完整回答由调用方丢弃。 */
	public record CompletedPair(String userText, String assistantText, String userUtteranceId,
			String assistantUtteranceId) {

		public boolean complete() {
			return userText != null && !userText.isBlank() && assistantText != null && !assistantText.isBlank();
		}
	}

	/**
	 * 组装上下文：system（冻结人设/开场白/语气）+ 最近 10 完整对 + 当前输入；超 64KiB 从最老整对裁剪； system+当前仍超 →
	 * 422（派发前拒绝，不静默截断当前输入）。
	 */
	public List<ChatMessage> assembleContext(ProfileRevisionRow profile, List<CompletedPair> history,
			String currentText) {
		if (currentText == null || currentText.isBlank()) {
			throw new IntelligenceException(422, "dh_invalid_input", "当前输入不能为空。");
		}
		List<CompletedPair> complete = new ArrayList<>();
		for (CompletedPair pair : history == null ? List.<CompletedPair>of() : history) {
			if (pair.complete()) {
				complete.add(pair);
			}
		}
		if (complete.size() > MAX_CONTEXT_PAIRS) {
			complete = complete.subList(complete.size() - MAX_CONTEXT_PAIRS, complete.size());
		}
		List<ChatMessage> messages = new ArrayList<>();
		messages.add(ChatMessage.system(systemPrompt(profile)));
		for (CompletedPair pair : complete) {
			messages.add(ChatMessage.user(pair.userText()));
			messages.add(new ChatMessage("assistant", pair.assistantText(), null));
		}
		messages.add(ChatMessage.user(currentText));
		while (utf8Bytes(messages) > MAX_CONTEXT_UTF8_BYTES && messages.size() > 3) {
			// 最老整对 = system 之后的前两条；裁剪永远整对进行（不裁半对）。
			messages.remove(1);
			messages.remove(1);
		}
		if (utf8Bytes(messages) > MAX_CONTEXT_UTF8_BYTES) {
			throw new IntelligenceException(422, "dh_invalid_input", "当前输入超出本轮上下文预算。");
		}
		return messages;
	}

	/**
	 * 计量流调用（冻结 provider）：输出累计超过 8000 码点即取消上游并按中断收尾（无 done 事件）——
	 * 不完整回答不进历史完整对，费用按实际状态结算。
	 */
	public Flux<TextStreamEvent> stream(ExecutionContext context, List<ChatMessage> messages, int maxOutputTokens) {
		AtomicInteger emitted = new AtomicInteger();
		return textClient.streamMeteredMessages(context.provider().provider(), context.provider().baseUrl(),
				context.decryptedKey(), context.provider().model(), messages, maxOutputTokens,
				context.provider().isByok(), null).takeWhile(event -> {
					if (event.type() != TextStreamEvent.Type.delta) {
						return true;
					}
					int length = event.delta() == null ? 0 : event.delta().codePointCount(0, event.delta().length());
					return emitted.addAndGet(length) <= MAX_OUTPUT_CODE_POINTS;
				});
	}

	/**
	 * 已审句段流：累计 delta → 句段切分 → TTS 前 {@link DigitalHumanTextPolicy#check}（带前段尾 32 码点
	 * 跨段检测）；high 命中即 422 dh_content_blocked 终止（已派发费用按实际状态，由调用方结算）。 流的 done 事件冲刷残余；无
	 * done（半流/超限中断）残余不冲刷——不完整内容不得送 TTS。
	 */
	public Flux<SpeechTextSegment> checkedSegments(Flux<TextStreamEvent> events, long contentEpoch) {
		AtomicReference<String> pending = new AtomicReference<>("");
		AtomicReference<String> previousTail = new AtomicReference<>("");
		AtomicInteger nextIndex = new AtomicInteger();
		AtomicBoolean cleanEnd = new AtomicBoolean(false);
		return Flux.defer(() -> events.concatMap(event -> {
			if (event.type() == TextStreamEvent.Type.done) {
				cleanEnd.set(true);
				List<SpeechTextSegment> flushed = new ArrayList<>();
				for (String text : policy.split(pending.get(), true)) {
					flushed.add(checked(text, previousTail, nextIndex, contentEpoch));
				}
				return Flux.fromIterable(flushed);
			}
			if (event.type() == TextStreamEvent.Type.delta && event.delta() != null) {
				pending.set(pending.get() + event.delta());
				List<SpeechTextSegment> ready = new ArrayList<>();
				for (String text : policy.split(pending.get(), false)) {
					ready.add(checked(text, previousTail, nextIndex, contentEpoch));
				}
				if (!ready.isEmpty()) {
					String consumed = ready.stream().map(SpeechTextSegment::text).reduce("",
							(left, right) -> left + right);
					pending.set(pending.get().substring(consumed.length()));
				}
				return Flux.fromIterable(ready);
			}
			return Flux.empty();
		}).takeUntil(segment -> cleanEnd.get()));
	}

	private SpeechTextSegment checked(String text, AtomicReference<String> previousTail, AtomicInteger nextIndex,
			long contentEpoch) {
		policy.check(previousTail.get(), text);
		previousTail.set(text);
		return new SpeechTextSegment(nextIndex.getAndIncrement(), text, contentEpoch);
	}

	private static String systemPrompt(ProfileRevisionRow profile) {
		StringBuilder prompt = new StringBuilder("你是用户的数字人创作助手。严格遵守以下人设：\n");
		if (profile.persona() != null && !profile.persona().isBlank()) {
			prompt.append(profile.persona().strip()).append('\n');
		}
		if (profile.tone() != null) {
			prompt.append("语气：").append(profile.tone().name()).append("。\n");
		}
		prompt.append("不要调用外部工具，不要输出思考过程，回复保持口语化短句。");
		return prompt.toString();
	}

	private static int utf8Bytes(List<ChatMessage> messages) {
		int total = 0;
		for (ChatMessage message : messages) {
			total += message.content() == null ? 0 : message.content().getBytes(StandardCharsets.UTF_8).length;
			// K08.1：每条消息 32 token 协议余量按 UTF-8 近似 96 字节计。
			total += 96;
		}
		return total;
	}
}
