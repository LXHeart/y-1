package com.grassland.trust.precedent;

import java.time.Instant;

/**
 * 脱敏判例（任务书 #74 卡 G，拍板 D5：登录可见 + 脱敏 + 不显示具体金额）。
 *
 * <p><b>构造性脱敏红线</b>：表与该记录<b>不存在</b> org/account/金额列——身份与金额从源头不进判例，
 * 不靠查询过滤。{@code rationaleDigest} 只含终局轮每票理由摘要（截 100 字），不含审判官账号；
 * {@code voteSummary} 只含各轮票数分布与熟手席计数。
 */
public record PrecedentCase(
        String id,
        String disputeId,
        String taskType,
        String taskPlatform,
        String disputeKind,
        String focus,
        String claimsSummary,
        String decision,
        String finalVia,
        String voteSummary,
        String rationaleDigest,
        Instant createdAt) {
}
