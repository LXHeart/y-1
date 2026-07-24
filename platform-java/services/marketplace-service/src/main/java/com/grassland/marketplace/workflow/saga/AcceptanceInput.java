package com.grassland.marketplace.workflow.saga;

/**
 * 接受报名资金预留 Saga 的输入（草场 Epic 4 Slice 4F / HLD 10.2）。全可序列化——Temporal workflow 参数。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code applicationId} = 报名 id；同时作 finance 的 {@code engagement_ref}（决策④：复用报名 id 作履约引用）。</li>
 *   <li>{@code merchantAccountId} = 任务 owner（接受操作的商家，断言 caller）——activity 重验 owner 自查用。</li>
 *   <li>{@code organizationId} = 任务所属 org——现签服务断言带 org 上下文，finance org 级授权用。</li>
 *   <li>{@code amountCents} = {@code task.bounty_cents}——reserve 金额。</li>
 * </ul>
 */
public record AcceptanceInput(
        String applicationId,
        String taskId,
        String merchantAccountId,
        String organizationId,
        long amountCents) {
}
