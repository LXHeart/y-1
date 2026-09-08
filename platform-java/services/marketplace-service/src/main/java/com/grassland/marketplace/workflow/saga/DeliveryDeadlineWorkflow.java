package com.grassland.marketplace.workflow.saga;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 交付看门狗 workflow（任务书 #96 C96-01 / D96-02）：接单不交付的「提醒 → 补救 → 终结」Timer 链。
 *
 * <p>编排（照 {@link ConfirmationWindowWorkflow} 范式，Timer 只触发 activity Command）：临截止提醒
 * （{@code DeliveryDeadlineExpiring}）→ 补救窗到期 {@code terminateDeliveryTimeout}（行级守卫重验 +
 * 资金释放 + 名额回收）。延期批准清空 DB 派发标记并后移截止，派发器按新截止补启新 workflow
 * （workflowId 含终结线 epoch）；旧 workflow 到点被行级守卫 abort——与延期/确认/取消并发单边胜出。
 */
@WorkflowInterface
public interface DeliveryDeadlineWorkflow {

    @WorkflowMethod
    DeliveryOutcome run(DeliveryDeadlineInput input);
}
