package com.grassland.intelligence.comedy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.creationcontext.CreationContextSnapshot;
import com.grassland.intelligence.creationcontext.CreationContextSnapshotRepository;
import com.grassland.intelligence.creationcontext.StoreBrandingPromptText;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Validates and renders the frozen task context consumed by comedy-script runs. */
@Service
public class ComedyTaskCreationContext {
    private final CreationContextSnapshotRepository snapshots;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public ComedyTaskCreationContext(CreationContextSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    public Mono<Binding> bind(UUID snapshotId, String accountId, String requestedPlatform) {
        if (snapshotId == null) {
            return Mono.error(new IntelligenceException(400, "任务创作必须绑定创作上下文快照"));
        }
        return snapshots.findById(snapshotId)
                .filter(snapshot -> accountId.equals(snapshot.accountId()))
                .switchIfEmpty(Mono.error(new IntelligenceException(403, "无权使用该创作上下文快照")))
                .map(snapshot -> binding(snapshot, requestedPlatform));
    }

    private Binding binding(CreationContextSnapshot snapshot, String requestedPlatform) {
        if (!"video".equals(snapshot.contentFormId())) {
            throw new IntelligenceException(409, "创作上下文不是视频任务");
        }
        if (requestedPlatform == null || !snapshot.platformId().equals(requestedPlatform.trim())) {
            throw new IntelligenceException(409, "请求平台与冻结的创作上下文不一致");
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("contextSnapshotId", snapshot.id());
        context.put("task", snapshot.taskSnapshot());
        context.put("platformRules", snapshot.platformRulesSnapshot());
        context.put("materials", snapshot.materialSnapshot());
        // 任务书 #24：门店品牌块随冻结上下文下发（无门店任务省略）。
        if (!snapshot.storeBrandingSnapshot().isEmpty()) {
            context.put("storeBranding", snapshot.storeBrandingSnapshot());
        }
        try {
            return new Binding(snapshot, ChatMessage.system(
                    "以下 JSON 是创作开始时冻结的权威视频任务上下文。必须遵守任务要求和平台规则，"
                            + "只能使用其中授权的素材信息，不得用当前配置或推测覆盖。\n"
                            + mapper.writeValueAsString(context)
                            + StoreBrandingPromptText.render(snapshot.storeBrandingSnapshot())));
        } catch (Exception error) {
            throw new IntelligenceException(500, "创作上下文无法序列化");
        }
    }

    public record Binding(CreationContextSnapshot snapshot, ChatMessage promptContext) {}
}
