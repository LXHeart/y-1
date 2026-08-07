package com.grassland.trust.judge;

/** 管理员更新审判官运营准入。boxed 字段用于识别缺失 JSON 值。 */
public record UpdateJudgeAdmissionRequest(Boolean admitted, Long expectedVersion, String reason) {}
