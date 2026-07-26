package com.grassland.trust.workflow;

/**
 * 审判 workflow 入参（草场 Epic 6 Slice 6C Phase C）。由 controller 读 {@code trust.adjudication.*} 配置折算后传入，
 * workflow 内不读 env（HLD 9.2 确定性铁律）。时长均秒；窗口概念 T+24h/T+48h，dev/test 经 env 缩短。
 *
 * @param disputeId            争议 id（也是 workflow 业务键）
 * @param voteWindowSeconds    每轮投票窗口（Timer 时长）
 * @param appealWindowSeconds  判决后上诉窗口
 * @param maxRounds            平票重开上限
 * @param csAwaitSeconds       客服终审最长等待（轮询上限，appeal/escalate 用）
 * @param csPollSeconds        客服终审轮询步长
 */
public record AdjudicationInput(
        String disputeId,
        long voteWindowSeconds,
        long appealWindowSeconds,
        int maxRounds,
        long csAwaitSeconds,
        long csPollSeconds) {}
