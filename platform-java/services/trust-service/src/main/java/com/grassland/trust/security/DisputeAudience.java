package com.grassland.trust.security;

import com.grassland.trust.dispute.DisputeCase;
import com.grassland.trust.judge.JudgeRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 「谁能读这份争议」的**唯一口径**。
 *
 * <p>存在的理由是一段真实教训：争议相关端点的授权此前按端点逐个手写，读侧与写侧各写各的，
 * 结果同一类缺陷出现了四次——
 * <ol>
 *   <li>{@code judge} 身份不在 identity 的 IdentityType 里 → 投票端点恒 403；</li>
 *   <li>{@code customer_service} 同理 → 客服终审恒 403；</li>
 *   <li>审判官能投票、却读不到 {@code getAdjudication}（看板显示「无权查询该争议」，投票按钮渲染不出来）；</li>
 *   <li>客服能终审、却读不到同一份快照（连「客服终审」折叠区都不渲染，终审在 UI 上完全不可达）。</li>
 * </ol>
 * 前两次是身份来源不一致，后两次是<b>写路径已授权、读路径没跟上</b>。
 * 共同根因是「没有一个地方回答『这份争议的读者是谁』」——本类就是那个地方。
 *
 * <p><b>约定</b>：新增任何「读争议」的端点都必须走这里，不要再就地写 {@code filter}。
 * 需要放宽受众时改这一处，全部读端点同时生效。
 *
 * <p>受众（满足其一即可读）：
 * <ul>
 *   <li><b>当事方</b>——断言 org == 争议 org（商家/推荐官）；</li>
 *   <li><b>marketplace 服务</b>——结算链路查是否有活跃争议（服务断言）；</li>
 *   <li><b>本轮面板审判官</b>——快照已按 D-10 脱敏，审判官正是其目标读者；</li>
 *   <li><b>客服/管理员</b>——平台职能，跨 org；读快照严格弱于其已被授权的「覆盖判决」。</li>
 * </ul>
 */
@Component
public class DisputeAudience {

    private final JudgeRepository judges;

    public DisputeAudience(JudgeRepository judges) {
        this.judges = judges;
    }

    /**
     * 该调用者能否读这份争议。
     *
     * <p>面板成员判定要查库，故返回 {@code Mono}；其余三类是纯断言判断，命中即短路不查库。
     */
    public Mono<Boolean> canRead(TrustCallerResolver.Caller caller, DisputeCase dispute) {
        if (caller.isServicePrincipal(TrustCallerResolver.MARKETPLACE_SERVICE)
                || caller.isCustomerService()
                || dispute.openedByAccountId().equals(caller.accountId())
                || dispute.organizationId().equals(caller.organizationId())
                // 任务书 #74 方案 α：商家开争议时被诉推荐官无 org 可匹配——落库 respondent_account_id
                // 后按账号判定（V16 之前与存量 NULL 行不受影响）。
                || (dispute.respondentAccountId() != null
                        && dispute.respondentAccountId().equals(caller.accountId()))) {
            return Mono.just(true);
        }
        return judges.isPanelMember(dispute.id(), dispute.round(), caller.accountId());
    }
}
