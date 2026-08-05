package com.grassland.trust.adjudication;

import com.grassland.trust.dispute.DisputeEvidence;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 证据脱敏器（GL-P2-TRUST-001 T2 / D-10）。把 {@link DisputeEvidence}（含 raw + uploader）转为
 * {@link RedactedEvidence}（审判官/客服安全视图）。
 *
 * <p><b>Provisional（D-10 占位）</b>：本轮做最小 PII 掩码（手机号 / 身份证 / 邮箱）+ 截图只回句柄。
 * HLD D-10 完整脱敏规则（账号→确定性伪名哈希、区域规则、证据摘要自动生成）为 DECISION REQUIRED，
 * 待终审定稿后替换本实现。
 */
@Component
public class CaseEvidenceRedactor {

    private static final Pattern ID_CARD = Pattern.compile("\\d{17}[\\dXx]");
    private static final Pattern PHONE = Pattern.compile("\\d{11}");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}");

    public RedactedEvidence redact(DisputeEvidence e) {
        return new RedactedEvidence(e.id(), e.kind(), e.caption(), redactContent(e));
    }

    public List<RedactedEvidence> redact(List<DisputeEvidence> evidence) {
        return evidence.stream().map(this::redact).toList();
    }

    private String redactContent(DisputeEvidence e) {
        if ("screenshot".equals(e.kind())) {
            // 截图：contentRef 是 intelligence media_reference id。trust 不回原字节，只给句柄，
            // 前端经鉴权链路（owner/用途/有效期校验）取图。
            return "media:" + e.contentRef();
        }
        // 文本/链接：优先用提交时已脱敏的 redactedRef，否则现场脱敏 contentRef。
        String text = (e.redactedRef() != null && !e.redactedRef().isBlank()) ? e.redactedRef() : e.contentRef();
        return maskPii(text);
    }

    private String maskPii(String text) {
        if (text == null) {
            return "";
        }
        String t = ID_CARD.matcher(text).replaceAll(m -> m.group().substring(0, 6) + "********");  // 保留前 6 位
        t = PHONE.matcher(t).replaceAll(m -> m.group().substring(0, 3) + "****" + m.group().substring(7));  // 138****1234
        t = EMAIL.matcher(t).replaceAll(m -> {
            int at = m.group().indexOf('@');
            return at <= 0 ? "***" + m.group().substring(at) : m.group().charAt(0) + "***" + m.group().substring(at);
        });
        return t;
    }
}
