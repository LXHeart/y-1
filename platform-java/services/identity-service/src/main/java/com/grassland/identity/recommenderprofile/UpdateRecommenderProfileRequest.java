package com.grassland.identity.recommenderprofile;

import java.util.List;

/**
 * 维护自己画像的请求体（整份覆盖，PUT 语义）。
 *
 * <p>标签与社交账号收的是**数组**，不是逗号串——前端拆分的口径和后端的不一定一致，
 * 拆分放在一处（前端输入框）比放在两处安全。空数组表示清空。
 */
public record UpdateRecommenderProfileRequest(
        String displayName,
        String bio,
        List<String> contentTags,
        List<String> domainTags,
        List<SocialAccount> socialAccounts
) {
    private static final int MAX_TAGS = 10;
    private static final int MAX_SOCIAL = 10;
    private static final int MAX_BIO = 500;

    public UpdateRecommenderProfileRequest {
        displayName = trimToNull(displayName);
        bio = trimToNull(bio);
        if (bio != null && bio.length() > MAX_BIO) {
            throw new IllegalArgumentException("bio too long");
        }
        contentTags = normalizeTags(contentTags);
        domainTags = normalizeTags(domainTags);
        socialAccounts = socialAccounts == null ? List.of() : List.copyOf(socialAccounts);
        if (socialAccounts.size() > MAX_SOCIAL) {
            throw new IllegalArgumentException("too many social accounts");
        }
    }

    /** 去空白、去空串、去重、限量——标签是自选的，不做这层清洗界面上很快就会脏。 */
    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        List<String> cleaned = tags.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (cleaned.size() > MAX_TAGS) {
            throw new IllegalArgumentException("too many tags");
        }
        return cleaned;
    }

    private static String trimToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
