package com.grassland.marketplace.workflow.saga;

/**
 * 交付看门狗 Saga 输入（任务书 #96 C96-01 / D96-02）。全可序列化——Temporal workflow 参数，
 * workflow 内禁读 env（HLD 9.2 确定性铁律，时长一律入参传入）。
 *
 * <p>时间轴（自派发时刻起）：{@code remainingToDeadlineSeconds} 后到交付截止 → {@code remainingToRemedyEndSeconds}
 * 后到补救窗终结线（两者都是 DB 快照与 now() 的差，补启时按剩余秒数重算，不重置窗口）。
 * {@code reminderLeadSeconds} = 临交付截止提醒前置（生产 24h）：剩余时长大于它时在
 * {@code deadline - lead} 处发一次 {@code DeliveryDeadlineExpiring}；0 或 ≥ 剩余时长 ⇒ 跳过提醒（补启已过提醒点）。
 */
public record DeliveryDeadlineInput(
        String applicationId,
        String taskId,
        String organizationId,
        long remainingToDeadlineSeconds,
        long remainingToRemedyEndSeconds,
        long reminderLeadSeconds) {

    public DeliveryDeadlineInput {
        remainingToDeadlineSeconds = Math.max(0, remainingToDeadlineSeconds);
        remainingToRemedyEndSeconds = Math.max(remainingToDeadlineSeconds, remainingToRemedyEndSeconds);
        reminderLeadSeconds = Math.max(0, reminderLeadSeconds);
    }
}
