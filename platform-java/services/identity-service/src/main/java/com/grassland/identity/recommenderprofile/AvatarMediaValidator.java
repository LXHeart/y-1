package com.grassland.identity.recommenderprofile;

import com.grassland.identity.auth.IdentityException;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 校验媒体是否能作为当前推荐官的头像（任务书 #29+#30 D6 复验契约）。
 *
 * <p>头像是账号级资产：归属只比 owner==account（无 org 维度）。purpose 必须是 avatar、
 * 状态 active、图片 MIME、未过期。任一不符 → 400（PUT 落库前拦截，防止把他人/未传完的媒体挂上画像）。
 */
@Component
public class AvatarMediaValidator {

    private static final String AVATAR_PURPOSE = "avatar";
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp");

    public AvatarMediaMetadata requireUsable(AvatarMediaMetadata media, String accountId) {
        boolean valid = media != null
                && accountId.equals(media.ownerAccountId())
                && AVATAR_PURPOSE.equals(media.purpose())
                && "active".equals(media.status())
                && ALLOWED_MIME_TYPES.contains(media.mimeType())
                && media.sizeBytes() > 0
                && (media.expiresAt() == null || media.expiresAt().isAfter(Instant.now()));
        if (!valid) {
            throw new IdentityException(400, "头像媒体与当前账号不匹配或尚未完成上传");
        }
        return media;
    }
}
