package com.grassland.intelligence.orchestration;

import java.io.Serializable;

/**
 * queryState 返回（纯 workflow 内存态，禁 activity/DB）。对账任务用它对照任务行终态：
 * stage 终值与行 phase 的映射见 {@link VideoProductionWorkflowImpl#STAGE_DONE} 族常量。
 */
public record VideoTaskState(
        String stage,
        String taskId,
        boolean selectionSubmitted,
        String cancelledReason,
        String lastError) implements Serializable {}
