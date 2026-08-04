package com.grassland.identity.kyb;

import com.grassland.identity.auth.IdentityException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * KYB 提交审核的共享逻辑。GL-P3-MERCHANT-001。
 *
 * <p>此前 `submit` 只改状态 + 发 outbox，**从不写 {@code kyb_verification_request}** —— 于是
 * {@code KybVerificationRequestRepository.create} 全仓无调用方、admin 队列 {@code findPending()} 恒空、
 * approve/reject 正常流程不可达。本类是审核入队的唯一通道，由 merchant-profile 与 withdrawal-account 共用。
 */
@Service
public class KybSubmissionService {

    private final KybVerificationRequestRepository requests;
    private final Duration reviewSla;

    public KybSubmissionService(
            KybVerificationRequestRepository requests,
            @Value("${identity.kyb.review-sla-seconds:259200}") long reviewSlaSeconds) {
        this.requests = requests;
        this.reviewSla = Duration.ofSeconds(reviewSlaSeconds);
    }

    /**
     * 入队一条审核请求。**调用方须在事务内调用**，与目标状态变更、outbox 追加同事务，
     * 保证「状态变更 ⇔ 审核行存在 ⇔ 事件入队」原子。
     *
     * <p>同 {@code (type, target)} 已有非终态请求时抛 409，不建第二行——否则重复点提交会在
     * admin 队列里堆出多条同目标待审，审核人无从判断哪条有效。
     */
    public Mono<KybVerificationRequest> enqueue(KybVerificationType type, String organizationId,
                                                UUID targetId, String requesterAccountId,
                                                List<UUID> materialIds) {
        String materials = materialIds == null || materialIds.isEmpty()
                ? null
                : materialIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(",", "[", "]"));
        return requests.findByTypeAndTarget(type.dbValue(), targetId)
                .flatMap(existing -> KybRequestStatus.isOpen(existing.status())
                        ? Mono.<KybVerificationRequest>error(new IdentityException(409, "已有待审核申请，请等待审核结果"))
                        : Mono.<KybVerificationRequest>empty())
                .switchIfEmpty(Mono.defer(() -> requests.create(organizationId, requesterAccountId,
                        type.dbValue(), targetId, materials, Instant.now().plus(reviewSla))));
    }

    /** 校验 merchant profile 提交前的必填字段，缺失则抛 400 并列出字段名。 */
    public static void requireCompleteProfile(MerchantProfile profile) {
        List<String> missing = new java.util.ArrayList<>();
        if (isBlank(profile.legalName())) {
            missing.add("法定名称");
        }
        if (isBlank(profile.unifiedSocialCreditCode())) {
            missing.add("统一社会信用代码");
        }
        if (isBlank(profile.legalPersonName())) {
            missing.add("法人姓名");
        }
        if (isBlank(profile.legalPersonIdNumber())) {
            missing.add("法人身份证号");
        }
        if (isBlank(profile.businessAddress())) {
            missing.add("经营地址");
        }
        if (!missing.isEmpty()) {
            throw new IdentityException(400, "资料不完整，缺少：" + String.join("、", missing));
        }
    }

    /** 校验证件类附件齐备，缺失则抛 400 并列出材料名。 */
    public static void requireDocuments(List<String> uploadedTypes) {
        List<String> missing = MerchantAttachmentType.requiredForSubmission().stream()
                .filter(t -> !uploadedTypes.contains(t.dbValue()))
                .map(MerchantAttachmentType::displayName)
                .toList();
        if (!missing.isEmpty()) {
            throw new IdentityException(400, "审核材料不全，缺少：" + String.join("、", missing));
        }
    }

    /** 解析路径/请求体中的 UUID，畸形值 → 400（此前 {@code UUID.fromString} 直接冒成 500）。 */
    public static UUID parseUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IdentityException(400, field + "不能为空");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IdentityException(400, field + "格式无效");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
