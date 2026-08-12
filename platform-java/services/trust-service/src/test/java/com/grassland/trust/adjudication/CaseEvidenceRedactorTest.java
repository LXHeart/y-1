package com.grassland.trust.adjudication;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.trust.dispute.DisputeEvidence;
import com.grassland.trust.dispute.EvidenceProperties;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * {@link CaseEvidenceRedactor} 脱敏单测（GL-P2-TRUST-001 T2）。锁住 PII 掩码与截图句柄规则。
 *
 */
class CaseEvidenceRedactorTest {

    private final CaseEvidenceRedactor redactor =
            new CaseEvidenceRedactor(new EvidenceProperties(365, "test-pseudonym-secret-32-characters"));
    private final Instant retention = Instant.now().plusSeconds(60);

    @Test
    void masksPhoneIdCardAndEmailInTextEvidence() {
        var e = evidence("text",
                "联系13812345678，身份证110101199001011234，邮箱foo@bar.com确认", null);
        RedactedEvidence r = redactor.redact(e);

        assertThat(r.content()).contains("138****5678");
        assertThat(r.content()).doesNotContain("13812345678");
        assertThat(r.content()).doesNotContain("110101199001011234");
        assertThat(r.content()).contains("f***@bar.com");
        assertThat(r.kind()).isEqualTo("text");
        assertThat(r.submittedByAlias()).startsWith("participant-");
    }

    @Test
    void screenshotEvidenceReturnsMediaHandleNotRawBytes() {
        var e = evidence("screenshot", "media-abc-123", "履约截图");
        RedactedEvidence r = redactor.redact(e);

        assertThat(r.content()).startsWith("media:").doesNotContain("media-abc-123");
        assertThat(r.caption()).isEqualTo("履约截图");
    }

    @Test
    void prefersExplicitRedactedRefWhenPresent() {
        // 提交时已脱敏的 redactedRef 优先于现场脱敏 contentRef（避免重复/不一致脱敏）
        var e = new DisputeEvidence("e1", "d1", "acct", "merchant", "text",
                "原文13812345678", "已脱敏文本", null, Instant.now(), retention);
        RedactedEvidence r = redactor.redact(e);

        assertThat(r.content()).isEqualTo("已脱敏文本");
    }

    @Test
    void masksBankCardUuidLabeledNameAddressAndLinkSecrets() {
        String text = "姓名：张三 地址：上海市浦东新区世纪大道100号 卡号6222021234567890123 "
                + "账号11111111-1111-1111-1111-111111111111";
        assertThat(redactor.maskText(text))
                .doesNotContain("张三", "世纪大道100号", "6222021234567890123",
                        "11111111-1111-1111-1111-111111111111")
                .contains("**** **** **** 0123", "[账号已脱敏]");

        String link = redactor.redactForStorage("link",
                "https://Example.com/path/13812345678?token=secret#fragment");
        assertThat(link).isEqualTo("https://example.com/path/138****5678");
    }

    @Test
    void pseudonymIsStableWithinCaseAndUnlinkableAcrossCases() {
        assertThat(redactor.pseudonym("case-1", "account-1"))
                .isEqualTo(redactor.pseudonym("case-1", "account-1"))
                .isNotEqualTo(redactor.pseudonym("case-2", "account-1"));
    }

    @Test
    void redactsTextEvenWithoutCaption() {
        // RedactedEvidence 剥离 uploader 身份（无 submittedByAccountId 字段）；caption 可空仍正常脱敏内容
        var e = evidence("text", "证据13800001111", null);
        RedactedEvidence r = redactor.redact(e);

        assertThat(r.caption()).isNull();
        assertThat(r.content()).contains("138****1111");
    }

    private DisputeEvidence evidence(String kind, String contentRef, String caption) {
        return new DisputeEvidence("e1", "d1", "uploader-acct", "merchant", kind,
                contentRef, null, caption, Instant.now(), retention);
    }
}
