package com.grassland.identity.kyb;

import com.grassland.identity.auth.IdentityException;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 校验媒体是否能作为当前商户的 KYB 附件。 */
@Component
public class KybMediaValidator {

    private static final String KYB_PURPOSE = "merchant_kyb";
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg", "image/png", "application/pdf");

    public KybMediaMetadata requireUsable(KybMediaMetadata media, String accountId, String organizationId) {
        boolean valid = media != null
                && accountId.equals(media.ownerAccountId())
                && organizationId.equals(media.organizationId())
                && KYB_PURPOSE.equals(media.purpose())
                && KYB_PURPOSE.equals(media.domainType())
                && organizationId.equals(media.domainId())
                && "active".equals(media.status())
                && ALLOWED_MIME_TYPES.contains(media.mimeType())
                && media.sizeBytes() > 0
                && (media.expiresAt() == null || media.expiresAt().isAfter(Instant.now()));
        if (!valid) {
            throw new IdentityException(400, "附件媒体与当前商户不匹配或尚未完成上传");
        }
        return media;
    }

    public String requireAllowedMime(String mimeType) {
        String normalized = mimeType == null ? null : mimeType.trim().toLowerCase(java.util.Locale.ROOT);
        if (!ALLOWED_MIME_TYPES.contains(normalized)) {
            throw new IdentityException(400, "KYB 文件仅支持 JPEG、PNG 或 PDF");
        }
        return normalized;
    }
}
