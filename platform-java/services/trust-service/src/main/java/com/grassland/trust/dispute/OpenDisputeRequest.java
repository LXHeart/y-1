package com.grassland.trust.dispute;

import java.util.List;
import java.util.UUID;

/**
 * 开争议请求（草场 Epic 6 Slice 6A / HLD 10.5 OpenDispute）。{@code engagementRef} 必填（marketplace applicationId）；
 * {@code reason} 可空。
 *
 * <p>切片 12 安全收口：{@code engagementRef} 在 HTTP 边界即校验为 UUID，避免非法值经 DB CAST 变 500
 * 或经 outbox 进入 Kafka 重试/DLT。canonical 字符串形式由 trust 持久化。
 *
 * <p>D-03 slice 2：{@code kind}（standard / merchant_rejection，默认 standard）。
 * {@code openedByAccountId} + {@code organizationId} 仅 marketplace 服务断言代商家开 merchant_rejection 争议时携带
 * （终端用户路径由 authorizer 解析 canonical org）。
 *
 * <p>GL-P2-TRUST-001 T1：{@code evidence} 可选——开争议时可一并提交初始证据（文本/截图句柄/外链）。
 * 缺省空列表；提交后在同一事务内落 dispute_evidence + 点亮 dispute_case.evidence_ref。
 */
public record OpenDisputeRequest(String engagementRef, String reason, String kind,
                                 String openedByAccountId, String organizationId,
                                 String recommenderAccountId, Boolean premiumSupportAtAccept,
                                 List<EvidenceItem> evidence) {

    /** 向后兼容：不带 evidence 的 5 参构造（既有调用方）。 */
    public OpenDisputeRequest(String engagementRef, String reason, String kind,
                              String openedByAccountId, String organizationId) {
        this(engagementRef, reason, kind, openedByAccountId, organizationId, null, null, null);
    }

    /** 向后兼容：带 evidence、但不带权益快照的既有调用方。 */
    public OpenDisputeRequest(String engagementRef, String reason, String kind,
                              String openedByAccountId, String organizationId,
                              List<EvidenceItem> evidence) {
        this(engagementRef, reason, kind, openedByAccountId, organizationId, null, null, evidence);
    }

    public OpenDisputeRequest {
        if (engagementRef == null || engagementRef.isBlank()) {
            throw new IllegalArgumentException("engagementRef is required");
        }
        try {
            engagementRef = UUID.fromString(engagementRef.trim()).toString();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("engagementRef must be a UUID");
        }
        if (kind == null || kind.isBlank()) {
            kind = "standard";
        }
        if (evidence == null) {
            evidence = List.of();
        }
        if (recommenderAccountId != null && !recommenderAccountId.isBlank()) {
            try {
                recommenderAccountId = UUID.fromString(recommenderAccountId.trim()).toString();
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("recommenderAccountId must be a UUID");
            }
        }
        // 校验每条证据项的字段，避免非法值落到 DB/outbox。
        for (EvidenceItem item : evidence) {
            if (item == null) {
                throw new IllegalArgumentException("evidence item 不能为空");
            }
            item.validate();
        }
    }

    /** 开争议时携带的初始证据项。{@code kind}=text/screenshot/link；{@code contentRef}=文本原文/media id/外链。 */
    public record EvidenceItem(String kind, String contentRef, String caption) {
        public void validate() {
            if (kind == null || kind.isBlank()) {
                throw new IllegalArgumentException("evidence.kind is required");
            }
            if (contentRef == null || contentRef.isBlank()) {
                throw new IllegalArgumentException("evidence.contentRef is required");
            }
        }
    }
}
