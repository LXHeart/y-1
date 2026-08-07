package com.grassland.trust.judge;

import java.time.Instant;

/** 平台管理员授予或撤销审判官运营准入的只追加审计记录。 */
public record JudgeAdmissionAudit(
        long id,
        String judgeId,
        String action,
        String actorAccountId,
        String reason,
        long previousVersion,
        long newVersion,
        Instant createdAt) {}
