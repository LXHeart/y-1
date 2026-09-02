package com.grassland.intelligence.orchestration;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * 视频成片管线 activity（任务书 #66 卡A1）：#64/#65 既有服务的薄壳，零业务决策。
 * 全部幂等——行级领单租约（FOR UPDATE SKIP LOCKED）保证与 legacy worker 互斥。
 */
@ActivityInterface
public interface VideoProductionActivities {

    /** 读任务行快照（对账/阶段探测；无 IO 副作用）。 */
    @ActivityMethod
    TaskSnapshot loadTask(String taskId, String accountId);

    /**
     * 领一批 take + audio 行按 #64 worker 语义推进一拍，返回全终态探测结果。
     * 空批（无行可领/全被 legacy 持租约）也返回快照——调用方据快照决定继续轮询或收口。
     */
    @ActivityMethod
    GenerationStatus driveGeneration(String taskId, String accountId);

    /** 领单（按 id，10 分钟租约下限同 legacy）后走 VideoCompositionService.compose（含结算/退款）。 */
    @ActivityMethod
    TaskSnapshot composeAndSettle(String taskId, String accountId);

    /** 选片等待超时：任务行 failed + handleFailure 释放预留（镜像 taskService.cancel 的退款语义）。 */
    @ActivityMethod
    void selectionTimeout(String taskId, String accountId);

    /** 任务行终态快照（phase/finalMediaId/actualCostCents 是对账抽样三字段）。 */
    record TaskSnapshot(String phase, int progress, boolean terminal, String finalMediaId,
            Integer actualCostCents, String errorCode) {}

    record GenerationStatus(TaskSnapshot task, boolean hasTakes, boolean allTakesTerminal,
            boolean allAudiosTerminal) {}
}
