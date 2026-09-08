package com.grassland.marketplace.taskcatalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 交付期限政策（任务书 #96 C96-01 / §5.3）：接受时快照进合同的三元组来源。
 *
 * <p>试点缺省：交付期限 7 天（§13 待拍板项——生产缺省天数主程未最终拍板，先以可配缺省落地，改配置即生效、
 * 不影响存量合同 D96-01）；补救窗 48h；临到期提醒前置 24h。秒数形配置便于 IT 拨快。
 * 合同字段（task.delivery_deadline_days，C96-04 落地）优先于本缺省——届时由快照调用方合并。
 */
@Component
public class EngagementDeliveryPolicy {

    /** D96-07：履约政策版本。非该版本接受的报名（NULL）不受期限/退出/终结规则约束，零回填。 */
    public static final int POLICY_VERSION = 1;

    private final long deliverySeconds;
    private final long remedySeconds;
    private final long reminderLeadSeconds;

    public EngagementDeliveryPolicy(
            @Value("${marketplace.engagement.delivery-deadline-days:7}") long deliveryDeadlineDays,
            @Value("${marketplace.engagement.delivery-remedy-seconds:172800}") long remedySeconds,
            @Value("${marketplace.engagement.reminder-lead-seconds:86400}") long reminderLeadSeconds) {
        this.deliverySeconds = Math.max(0, deliveryDeadlineDays) * 86400L;
        this.remedySeconds = Math.max(0, remedySeconds);
        this.reminderLeadSeconds = Math.max(0, reminderLeadSeconds);
    }

    /** accept 时冻结的合同快照（policyVersion + 交付/补救窗秒数）。 */
    public TaskApplicationRepository.DeliveryContract contract() {
        return new TaskApplicationRepository.DeliveryContract(POLICY_VERSION, deliverySeconds, remedySeconds);
    }

    /**
     * 合同字段优先（任务书 #96 C96-04 / §5.3）：task.delivery_deadline_days 非空则覆盖配置缺省天数；
     * 补救窗无合同字段，恒走配置。
     */
    public TaskApplicationRepository.DeliveryContract contractFor(Task task) {
        if (task.deliveryDeadlineDays() == null) {
            return contract();
        }
        long seconds = Math.max(0, task.deliveryDeadlineDays()) * 86400L;
        return new TaskApplicationRepository.DeliveryContract(POLICY_VERSION, seconds, remedySeconds);
    }

    public long reminderLeadSeconds() {
        return reminderLeadSeconds;
    }
}
