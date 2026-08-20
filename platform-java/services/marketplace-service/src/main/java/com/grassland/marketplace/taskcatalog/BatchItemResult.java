package com.grassland.marketplace.taskcatalog;

import java.util.LinkedHashMap;
import java.util.Map;

/** 任务书 #27：批量操作逐项结果。#26 D12：{@code taskClosed} = 该项接受是否同事务触发满员自动关闭。 */
record BatchItemResult(String applicationId, String outcome, String commandId,
                       String workflowId, String reason, boolean taskClosed) {
    static BatchItemResult ofOutcome(String appId, String outcome) {
        return new BatchItemResult(appId, outcome, null, null, null, false);
    }

    static BatchItemResult failed(String appId, String reason) {
        return new BatchItemResult(appId, "failed", null, null, reason, false);
    }

    Map<String, Object> toBody() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("applicationId", applicationId);
        m.put("outcome", outcome);
        if (commandId != null) m.put("commandId", commandId);
        if (workflowId != null) m.put("workflowId", workflowId);
        if (reason != null) m.put("reason", reason);
        m.put("taskClosed", taskClosed);
        return m;
    }
}
