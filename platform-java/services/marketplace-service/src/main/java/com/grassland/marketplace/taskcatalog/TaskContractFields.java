package com.grassland.marketplace.taskcatalog;

import java.util.Map;
import java.util.Set;

/**
 * 发布合同字段（任务书 #96 C96-04 / §7 V58）：发布表单可填，缺省走配置。
 *
 * <ul>
 *   <li>{@code reviewRequired}：合同要求发布前审稿（草稿送审→商家批准后才交发布凭证）。</li>
 *   <li>{@code deliveryDeadlineDays}：交付期限天数（accept 时快照，缺省走
 *       {@code marketplace.engagement.delivery-deadline-days} 配置）。</li>
 *   <li>{@code cancelPolicy}：取消条款阶段比例模板 bps（script/deliverable/published，0..10000），
 *       取消/超时部分结算优先于全局配置缺省。</li>
 * </ul>
 * 请求体平铺携带（{@code reviewRequired}/{@code deliveryDeadlineDays}/{@code cancelPolicy}），校验在此。
 */
public final class TaskContractFields {

    public static final Set<String> CANCEL_POLICY_KEYS = Set.of("script", "deliverable", "published");

    private TaskContractFields() {
    }

    /** 校验并规范化取消条款模板：key 受控、bps 整数 0..10000；返回原 map（不可变拷贝）。 */
    public static Map<String, Integer> validateCancelPolicy(Map<String, Integer> cancelPolicy) {
        if (cancelPolicy == null) {
            return null;
        }
        for (Map.Entry<String, Integer> entry : cancelPolicy.entrySet()) {
            if (!CANCEL_POLICY_KEYS.contains(entry.getKey())) {
                throw new IllegalArgumentException("取消条款仅支持 script/deliverable/published");
            }
            Integer bps = entry.getValue();
            if (bps == null || bps < 0 || bps > 10_000) {
                throw new IllegalArgumentException("取消条款比例须在 0..10000 bps 内");
            }
        }
        return Map.copyOf(cancelPolicy);
    }
}
