package com.grassland.intelligence.ai.run;

import java.time.Instant;
import java.util.UUID;

/**
 * 任务上下文快照（TaskContext，HLD §5.6 / §6.2）。
 *
 * <p>Run 起始冻结的执行期解析结果——使每条 Run 可复现、计费口径冻结（D-11 priceTableVersion + §6.2 模型版本快照）。
 * 随 {@code GET /api/ai/runs/{id}} 返回供运营审计。{@code resolutionType} 由 {@code platformModelVersion} 推导
 * （平台 run 冻结版本；BYOK run 为 null）。
 */
public record TaskContext(
        UUID runId,
        String capability,
        String provider,
        String model,
        String resolutionType,        // PLATFORM / BYOK（由 platformModelVersion 推导）
        String priceTableVersion,
        Integer platformModelVersion,
        boolean fallbackAuthorized,
        UUID contextSnapshotId,
        String creditsCentsPolicyVersion,
        Instant startedAt) {

    public static TaskContext from(AiRun run) {
        return new TaskContext(
                run.id(),
                run.capability(),
                run.provider(),
                run.model(),
                run.platformModelVersion() != null ? "PLATFORM" : "BYOK",
                run.priceTableVersion(),
                run.platformModelVersion(),
                run.fallbackAuthorized(),
                run.contextSnapshotId(),
                run.creditsCentsPolicyVersion(),
                run.startedAt());
    }
}
