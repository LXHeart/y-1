package com.grassland.identity.kyb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.identity.auth.IdentityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * KYB 状态机与类型解析。GL-P3-MERCHANT-001。
 *
 * <p>锁住的关键语义：**rejected 可编辑可重新提交、只有 approved 是终态**。
 * 此前 rejected 也算终态且只有 draft/pending 可编辑 → 被拒商家永久锁死，无复审路径。
 */
class KybStatusTest {

    @Test
    @DisplayName("merchant profile：draft 与 rejected 可编辑可提交")
    void merchantEditableStates() {
        assertThat(MerchantProfileStatus.DRAFT.isEditable()).isTrue();
        assertThat(MerchantProfileStatus.REJECTED.isEditable()).isTrue();
        assertThat(MerchantProfileStatus.DRAFT.canSubmit()).isTrue();
        assertThat(MerchantProfileStatus.REJECTED.canSubmit()).isTrue();

        assertThat(MerchantProfileStatus.PENDING.isEditable()).isFalse();
        assertThat(MerchantProfileStatus.UNDER_REVIEW.isEditable()).isFalse();
        assertThat(MerchantProfileStatus.APPROVED.isEditable()).isFalse();
    }

    @Test
    @DisplayName("merchant profile：只有 approved 是终态（rejected 不是，否则被拒即锁死）")
    void merchantOnlyApprovedIsTerminal() {
        assertThat(MerchantProfileStatus.APPROVED.isTerminal()).isTrue();
        assertThat(MerchantProfileStatus.REJECTED.isTerminal()).isFalse();
        assertThat(MerchantProfileStatus.PENDING.isUnderReview()).isTrue();
        assertThat(MerchantProfileStatus.UNDER_REVIEW.isUnderReview()).isTrue();
        assertThat(MerchantProfileStatus.DRAFT.isUnderReview()).isFalse();
    }

    @Test
    @DisplayName("withdrawal account：pending/rejected 可编辑可删，approved 不可")
    void withdrawalEditableStates() {
        assertThat(WithdrawalAccountStatus.PENDING.isEditable()).isTrue();
        assertThat(WithdrawalAccountStatus.REJECTED.isEditable()).isTrue();
        assertThat(WithdrawalAccountStatus.PENDING.isDeletable()).isTrue();
        assertThat(WithdrawalAccountStatus.REJECTED.isDeletable()).isTrue();

        assertThat(WithdrawalAccountStatus.UNDER_REVIEW.isEditable()).isFalse();
        assertThat(WithdrawalAccountStatus.UNDER_REVIEW.isDeletable()).isFalse();
        assertThat(WithdrawalAccountStatus.APPROVED.isEditable()).isFalse();
        assertThat(WithdrawalAccountStatus.APPROVED.isDeletable()).isFalse();
        assertThat(WithdrawalAccountStatus.APPROVED.isTerminal()).isTrue();
        assertThat(WithdrawalAccountStatus.REJECTED.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("kyb request：approved/rejected 终态，isOpen 只对未审完为真")
    void requestStatus() {
        assertThat(KybRequestStatus.isOpen("pending")).isTrue();
        assertThat(KybRequestStatus.isOpen("under_review")).isTrue();
        assertThat(KybRequestStatus.isOpen("approved")).isFalse();
        assertThat(KybRequestStatus.isOpen("rejected")).isFalse();
        assertThat(KybRequestStatus.fromDb("APPROVED")).isEqualTo(KybRequestStatus.APPROVED);
    }

    @Test
    @DisplayName("aggregateType 是 PascalCase，不带下划线残留")
    void aggregateTypeIsPascalCase() {
        assertThat(KybVerificationType.MERCHANT_PROFILE.aggregateType()).isEqualTo("MerchantProfile");
        assertThat(KybVerificationType.WITHDRAWAL_ACCOUNT.aggregateType()).isEqualTo("WithdrawalAccount");
        assertThat(KybVerificationType.STORE_PROFILE.aggregateType()).isEqualTo("StoreProfile");
    }

    @Test
    @DisplayName("附件类型非法值 → 400 而非 500")
    void invalidAttachmentTypeIsBadRequest() {
        assertThatThrownBy(() -> MerchantAttachmentType.fromRequest("not_a_type"))
                .isInstanceOf(IdentityException.class)
                .satisfies(e -> assertThat(((IdentityException) e).status()).isEqualTo(400));
    }

    @Test
    @DisplayName("畸形 UUID → 400 而非 500")
    void malformedUuidIsBadRequest() {
        assertThatThrownBy(() -> KybSubmissionService.parseUuid("not-a-uuid", "附件 ID"))
                .isInstanceOf(IdentityException.class)
                .satisfies(e -> assertThat(((IdentityException) e).status()).isEqualTo(400));
        assertThatThrownBy(() -> KybSubmissionService.parseUuid(null, "附件 ID"))
                .isInstanceOf(IdentityException.class)
                .satisfies(e -> assertThat(((IdentityException) e).status()).isEqualTo(400));
    }

    @Test
    @DisplayName("材料不全 → 400 并列出缺失项")
    void missingDocumentsListed() {
        assertThatThrownBy(() -> KybSubmissionService.requireDocuments(java.util.List.of("business_license")))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("法人证件正面")
                .hasMessageContaining("法人证件反面");

        KybSubmissionService.requireDocuments(java.util.List.of(
                "business_license", "legal_person_id_front", "legal_person_id_back"));
    }
}
