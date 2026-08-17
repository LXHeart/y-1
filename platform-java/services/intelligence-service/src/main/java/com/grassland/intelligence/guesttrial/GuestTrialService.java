package com.grassland.intelligence.guesttrial;

import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.TextCompletionCommand;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 游客试用三能力的 provider 调用（R5）。全部走既有 {@link AiCapabilityAdapter}（reactive，无新增阻塞边界），
 * 非流式聚合 + 固定 JSON 解析（试用价值密度优先，流式逐 token 属登录后体验）。
 * prompt 为试用专用裁剪版（{@link GuestTrialPrompts}），不改既有文件。
 */
@Component
public class GuestTrialService {

    /** 多模态/长文完成的超时（试用面非流式，超时即 provider_error，不烧额度）。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String FAILURE = "试用生成失败，请稍后再试";

    private final AiCapabilityAdapter ai;

    public GuestTrialService(AiCapabilityAdapter ai) {
        this.ai = ai;
    }

    /** article-titles：主题 → 5 个候选标题 JSON。 */
    public Mono<String> titles(String topic) {
        return complete(GuestTrialPrompts.titles(topic));
    }

    /** content-score：文案 → 5 维评分 JSON。 */
    public Mono<String> score(String content) {
        return complete(GuestTrialPrompts.score(content));
    }

    /** image-review：探店照片 base64 → 点评草稿 JSON。base64 进 buffer 前已限长（controller）。 */
    public Mono<String> imageReview(String imageBase64, String mimeType) {
        List<ContentPart> parts = new ArrayList<>();
        parts.add(ContentPart.image("data:" + mimeType + ";base64," + imageBase64));
        parts.add(ContentPart.text(GuestTrialPrompts.imageReviewInstruction()));
        return ai.completeMultimodal(parts, TIMEOUT)
                .map(GuestTrialService::requireJson)
                .onErrorMap(this::asProviderError);
    }

    private Mono<String> complete(List<com.grassland.intelligence.ai.ChatMessage> messages) {
        return ai.completeText(new TextCompletionCommand(messages, FAILURE, TIMEOUT))
                .map(GuestTrialService::requireJson)
                .onErrorMap(this::asProviderError);
    }

    /** 试用帧契约：result 载荷必须是 JSON 对象（剥 markdown code fence；非对象 → provider_error）。 */
    private static String requireJson(String raw) {
        String stripped = raw == null ? "" : raw.trim();
        if (stripped.startsWith("```")) {
            int start = stripped.indexOf('\n');
            int end = stripped.lastIndexOf("```");
            if (start >= 0 && end > start) {
                stripped = stripped.substring(start + 1, end).trim();
            }
        }
        if (!stripped.startsWith("{") || !stripped.endsWith("}")) {
            throw new IntelligenceException(502, "试用生成返回了无法解析的内容");
        }
        return stripped;
    }

    private Throwable asProviderError(Throwable error) {
        return error instanceof IntelligenceException ? error : new IntelligenceException(502, FAILURE);
    }
}
