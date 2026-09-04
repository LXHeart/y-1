package com.grassland.trust.workflow;

/**
 * 审判 workflow 入参（草场 Epic 6 Slice 6C Phase C + 任务书 #74 卡 B/C/F）。由 starter 读
 * {@code trust.adjudication.*} 配置与争议当前状态折算后传入，workflow 内不读 env（HLD 9.2 确定性铁律）。
 * 时长均秒；窗口概念 T+24h/T+48h，dev/test 经 env 缩短。
 *
 * @param disputeId              争议 id（也是 workflow 业务键）
 * @param voteWindowSeconds      每轮投票窗口（Timer 时长）
 * @param appealWindowSeconds    判决后上诉窗口
 * @param maxRounds              平票重开上限
 * @param csAwaitSeconds         客服终审最长等待（轮询上限，appeal/escalate 用）
 * @param csPollSeconds          客服终审轮询步长
 * @param evidencePhase          任务书 #74 卡 B：首段是否进入质证等待（court 通道受理期新启 run=true；
 *                               retrial 重启/存量已开庭补启 run=false）
 * @param evidenceWindowSeconds  质证窗秒数（Timer；0=跳过质证段直接开庭，测试哨兵）
 * @param startRound             本 run 投票循环起始轮（新案=1；发回重审重启=新轮次，卡 F）
 */
public record AdjudicationInput(
        String disputeId,
        long voteWindowSeconds,
        long appealWindowSeconds,
        int maxRounds,
        long csAwaitSeconds,
        long csPollSeconds,
        boolean evidencePhase,
        long evidenceWindowSeconds,
        int startRound) {

    /** 既有 6 参调用方兼容：无质证段、从第 1 轮开跑。 */
    public AdjudicationInput(String disputeId, long voteWindowSeconds, long appealWindowSeconds,
                             int maxRounds, long csAwaitSeconds, long csPollSeconds) {
        this(disputeId, voteWindowSeconds, appealWindowSeconds, maxRounds, csAwaitSeconds, csPollSeconds,
                false, 0, 1);
    }
}
