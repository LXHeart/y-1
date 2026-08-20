package com.grassland.marketplace.workflow;

import com.grassland.http.ManagedWebClientFactory;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.security.ServiceAssertionIssuer;
import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskRequirements;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 评论类互动的 L1 词库审核客户端（缺口清偿之九，ADR-D13 R5 放开项）：评论任务的评论文本提交时
 * 同步过 intelligence 内容安全词库（`POST /internal/content-safety/comment-check`，服务断言）。
 *
 * <p>姿态：**blocked 才拒**（400「评论内容未通过内容安全检查」，advisory 对齐 ADR-D16 D6——
 * 词库 low/medium 命中不拦截，商家人审截图仍可见原文）；intelligence 不可用 → fail-open
 * 放行并告警（提交是低频操作，词库检查是附加闸门而非唯一闸门）。guard() 对非评论任务直接放行。
 */
@Component
public class IntelligenceCommentSafetyClient {

    private static final Logger log = LoggerFactory.getLogger(IntelligenceCommentSafetyClient.class);
    private static final ParameterizedTypeReference<Envelope<CommentCheck>> CHECK_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final ServiceAssertionIssuer issuer;
    private final String headerName;

    public IntelligenceCommentSafetyClient(ServiceAssertionIssuer issuer,
            @Value("${intelligence.service.base-url:http://intelligence-service:8086}") String baseUrl,
            @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.webClient = ManagedWebClientFactory.create(IntelligenceCommentSafetyClient.class, baseUrl);
        this.issuer = issuer;
        this.headerName = headerName;
    }

    /** 提交闸门：评论任务带文本时同步词库检查；非评论任务/空文本直接放行。 */
    public Mono<Void> guard(Task task, String commentText) {
        return Mono.defer(() -> {
            TaskRequirements.Interaction interaction =
                    task.requirements() == null ? null : task.requirements().interaction();
            boolean commentTask = interaction != null && "comment".equals(interaction.actionType());
            if (!commentTask || commentText == null || commentText.isBlank()) {
                return Mono.empty();
            }
            return check(task.organizationId(), commentText.trim())
                    .flatMap(result -> result.blocked()
                            ? Mono.error(new MarketplaceException(
                                    400, "评论内容未通过内容安全检查，请修改后提交"))
                            : Mono.empty());
        });
    }

    private Mono<CommentCheck> check(String organizationId, String text) {
        return webClient.post()
                .uri("/internal/content-safety/comment-check")
                .header(headerName, issuer.issueForOrg(organizationId, "grassland-intelligence"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", text))
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(CHECK_TYPE).map(Envelope::data);
                    }
                    // fail-open：审核服务不可用不拦截提交（词库是附加闸门），告警留痕。
                    log.warn("comment safety check unavailable status={} orgTextLen={}",
                            response.statusCode().value(), text.length());
                    return response.releaseBody().then(Mono.just(new CommentCheck(false, 0)));
                })
                .onErrorResume(error -> {
                    log.warn("comment safety check failed open", error);
                    return Mono.just(new CommentCheck(false, 0));
                });
    }

    /** intelligence 信封。 */
    private record Envelope<T>(boolean success, T data) {}

    /** 词库检查结论：blocked = high 命中；findings 数量仅供日志。 */
    public record CommentCheck(boolean blocked, int findings) {}

}
