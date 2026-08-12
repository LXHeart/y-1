package com.grassland.trust.adjudication;

import com.grassland.trust.dispute.DisputeEvidence;
import com.grassland.trust.dispute.EvidenceProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** D-10 evidence presentation policy shared by adjudication, support, and evidence APIs. */
@Component
public final class CaseEvidenceRedactor {

    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{6}(?:19|20)\\d{9}[\\dXx](?!\\d)");
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}");
    private static final Pattern UUID = Pattern.compile("(?i)(?<![0-9a-f])[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?![0-9a-f])");
    private static final Pattern LABELED_NAME = Pattern.compile("(姓名|联系人|法人|收件人)([：:]?\\s*)[\\p{IsHan}·]{2,20}");
    private static final Pattern LABELED_ADDRESS = Pattern.compile(
            "(地址|住址|收货地址)([：:]?\\s*)[^，,；;\\n]{4,120}?(?=\\s+(?:卡号|银行卡号|账号)[：:]?|[，,；;\\n]|$)");

    private final byte[] pseudonymSecret;

    public CaseEvidenceRedactor(EvidenceProperties properties) {
        this.pseudonymSecret = properties.pseudonymSecret().getBytes(StandardCharsets.UTF_8);
    }

    public RedactedEvidence redact(DisputeEvidence evidence) {
        return new RedactedEvidence(
                evidence.id(),
                normalizedKind(evidence.kind()),
                evidence.caption() == null ? null : maskText(evidence.caption()),
                redactContent(evidence),
                pseudonym(evidence.disputeId(), evidence.submittedByAccountId()),
                safeRole(evidence.submittedByRole()));
    }

    public List<RedactedEvidence> redact(List<DisputeEvidence> evidence) {
        return evidence.stream().map(this::redact).toList();
    }

    /** Creates the immutable redacted snapshot stored next to raw evidence. */
    public String redactForStorage(String kind, String contentRef) {
        return switch (normalizedKind(kind)) {
            case "screenshot" -> "media:" + opaqueHandle(contentRef);
            case "link" -> redactLink(contentRef);
            default -> maskText(contentRef);
        };
    }

    public String maskText(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        String value = ID_CARD.matcher(text).replaceAll(match ->
                match.group().substring(0, 3) + "***********" + match.group().substring(14));
        value = BANK_CARD.matcher(value).replaceAll(match ->
                "**** **** **** " + match.group().substring(match.group().length() - 4));
        value = PHONE.matcher(value).replaceAll(match ->
                match.group().substring(0, 3) + "****" + match.group().substring(7));
        value = EMAIL.matcher(value).replaceAll(match -> {
            int at = match.group().indexOf('@');
            return match.group().substring(0, 1) + "***" + match.group().substring(at);
        });
        value = LABELED_NAME.matcher(value).replaceAll("$1$2**");
        value = LABELED_ADDRESS.matcher(value).replaceAll("$1$2[已脱敏]");
        return UUID.matcher(value).replaceAll("[账号已脱敏]");
    }

    /** Stable only within one dispute and one environment. */
    public String pseudonym(String disputeId, String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return "participant-unknown";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pseudonymSecret, "HmacSHA256"));
            byte[] digest = mac.doFinal(((disputeId == null ? "" : disputeId) + ":" + accountId)
                    .getBytes(StandardCharsets.UTF_8));
            return "participant-" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (Exception error) {
            throw new IllegalStateException("unable to generate evidence pseudonym", error);
        }
    }

    public String summary(List<DisputeEvidence> evidence) {
        long text = evidence.stream().filter(item -> "text".equals(normalizedKind(item.kind()))).count();
        long screenshots = evidence.stream().filter(item -> "screenshot".equals(normalizedKind(item.kind()))).count();
        long links = evidence.stream().filter(item -> "link".equals(normalizedKind(item.kind()))).count();
        return "共 " + evidence.size() + " 条证据（文本 " + text + "、截图 " + screenshots + "、链接 " + links + "）";
    }

    private String redactContent(DisputeEvidence evidence) {
        String snapshot = evidence.redactedRef();
        if (snapshot != null && !snapshot.isBlank()) {
            return maskText(snapshot);
        }
        return redactForStorage(evidence.kind(), evidence.contentRef());
    }

    private String redactLink(String raw) {
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return "[外链已脱敏]";
            }
            String path = uri.getPath() == null ? "" : maskText(uri.getPath());
            return scheme.toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT) + path;
        } catch (RuntimeException invalid) {
            return "[外链已脱敏]";
        }
    }

    private static String opaqueHandle(String value) {
        if (value == null || value.isBlank()) {
            return "missing";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (Exception error) {
            throw new IllegalStateException("unable to redact media handle", error);
        }
    }

    private static String normalizedKind(String kind) {
        if (kind == null) {
            return "text";
        }
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "screenshot" -> "screenshot";
            case "link" -> "link";
            default -> "text";
        };
    }

    private static String safeRole(String role) {
        if (role == null) {
            return "party";
        }
        return switch (role.toLowerCase(Locale.ROOT)) {
            case "merchant" -> "merchant";
            case "recommender" -> "recommender";
            case "customer_service" -> "customer_service";
            case "marketplace" -> "service";
            default -> "party";
        };
    }
}
