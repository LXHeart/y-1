package com.grassland.intelligence.contentsafety;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.contentsafety.ContentSafetyFixController.FixRequest;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 内容安全修复编排（任务书 #63 卡2 / P1-P3 拍板）：把问题清单交给模型改写出完整正文，
 * 聚合型 SSE（progress/result 帧）下发——moments/card-series 同款契约。
 *
 * <p>计费语义（P2）：经 {@link FrozenTextExecutionService#executeFree} 免费创作分支——
 * capability=content_fix 恒用平台模型（不进 BYOK 白名单），feature=null 平台资助 0 积分，
 * ai_run 留痕，预算闸/并发槽照常生效；未配置模型 → denied 以 503 先于 SSE（controller 映射）。
 */
@Service
public class ContentSafetyFixService {

    static final String CAPABILITY = "content_fix";
    static final int MAX_OUTPUT_TOKENS = 4096;

    private final FrozenTextExecutionService frozenText;
    // intelligence-service 没有全局 ObjectMapper bean——自持实例（照 ArticleCreationContext 姿态）
    private final ObjectMapper mapper = new ObjectMapper();

    public ContentSafetyFixService(FrozenTextExecutionService frozenText) {
        this.frozenText = frozenText;
    }

    /** 执行完成后再发 SSE 帧：progress 占位 + result 全文。上游失败经执行环退款后以异常透出。 */
    public Mono<Flux<String>> fix(FixRequest request, ServerWebExchange exchange) {
        List<ChatMessage> messages = List.of(
                ChatMessage.system(ContentSafetyFixPrompts.system(request)),
                ChatMessage.user(ContentSafetyFixPrompts.user(request)));
        return frozenText.executeFree(exchange, CAPABILITY, messages, MAX_OUTPUT_TOKENS,
                        completion -> completion.content() == null ? "" : completion.content())
                .map(text -> Flux.concat(Mono.just(progressFrame()), Mono.just(resultFrame(text))));
    }

    private String progressFrame() {
        try {
            return mapper.writeValueAsString(Map.of("type", "progress"));
        } catch (Exception error) {
            return "{\"type\":\"progress\"}";
        }
    }

    private String resultFrame(String text) {
        try {
            return mapper.writeValueAsString(Map.of("type", "result", "text", text));
        } catch (Exception error) {
            return "{\"type\":\"result\",\"text\":\"\"}";
        }
    }
}
