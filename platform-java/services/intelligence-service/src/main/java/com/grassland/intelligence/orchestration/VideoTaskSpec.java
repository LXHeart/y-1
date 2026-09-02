package com.grassland.intelligence.orchestration;

import java.io.Serializable;

/**
 * 视频成片管线 workflow 入参（任务书 #66 卡A1）。
 *
 * <p>workflow 代码禁止读配置——节奏参数（轮询间隔/选片等待上限）由提交侧从配置读出后随 spec
 * 传入，重放时历史里带的是当时值。id 一律 String：workflow 载荷不依赖 UUID 转换约定。
 */
public record VideoTaskSpec(
        String taskId,
        String storyboardId,
        String accountId,
        String organizationId,
        String mode,
        /** initial = 建任务首发；reroll = #65 卡6 成片后重抽驱动的第二春。 */
        String kind,
        int recomposeSeq,
        long pollIntervalMs,
        long selectionTimeoutSeconds) implements Serializable {

    public static final String KIND_INITIAL = "initial";
    public static final String KIND_REROLL = "reroll";
}
