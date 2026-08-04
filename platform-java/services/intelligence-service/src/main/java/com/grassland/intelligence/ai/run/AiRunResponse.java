package com.grassland.intelligence.ai.run;

import java.time.Instant;
import java.util.UUID;

/**
 * AI Run 响应（控制面 /api/ai/runs）。
 *
 * <p>{@code summary} 用于 GET 列表/详情（不含 completion 内容）；{@code executed} 用于 POST 执行结果
 * （含 content + usage）。{@code taskContext} 为冻结的执行期快照（HLD §6.2）。
 */
public record AiRunResponse(
        UUID runId,
        String capability,
        String provider,
        String model,
        String status,
        Integer actualCents,
        Instant startedAt,
        Instant completedAt,
        TaskContext taskContext,
        String content,
        Integer inputTokens,
        Integer outputTokens) {

    public static AiRunResponse summary(AiRun run) {
        return new AiRunResponse(run.id(), run.capability(), run.provider(), run.model(), run.status(),
                run.actualCents(), run.startedAt(), run.completedAt(), TaskContext.from(run), null, null, null);
    }

    public static AiRunResponse executed(AiRun run, TextCompletionResult result) {
        return new AiRunResponse(run.id(), run.capability(), run.provider(), run.model(), run.status(),
                run.actualCents(), run.startedAt(), run.completedAt(), TaskContext.from(run),
                result.content(), result.inputTokens(), result.outputTokens());
    }
}
