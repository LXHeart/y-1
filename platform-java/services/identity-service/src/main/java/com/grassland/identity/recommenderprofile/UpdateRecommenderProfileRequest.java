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
        List<SocialAccount> socialAccounts,
        String residentCity,
        List<String> serviceRegions,
        String contentPreferences,
        List<WorkSample> workSamples,
        String avatarMediaId
) {
    private static final int MAX_TAGS = 10;
    private static final int MAX_SOCIAL = 10;
    private static final int MAX_BIO = 500;
    private static final int MAX_REGIONS = 20;
    private static final int MAX_CITY = 64;
    private static final int MAX_PREFERENCES = 500;
    private static final int MAX_WORK_SAMPLES = 10;

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
        residentCity = trimToNull(residentCity);
        if (residentCity != null && residentCity.length() > MAX_CITY) {
            throw new IllegalArgumentException("resident city too long");
        }
        serviceRegions = normalizeRegions(serviceRegions);
        contentPreferences = trimToNull(contentPreferences);
        if (contentPreferences != null && contentPreferences.length() > MAX_PREFERENCES) {
            throw new IllegalArgumentException("content preferences too long");
        }
        workSamples = workSamples == null ? List.of() : List.copyOf(workSamples);
        if (workSamples.size() > MAX_WORK_SAMPLES) {
            throw new IllegalArgumentException("too many work samples");
        }
        avatarMediaId = trimToNull(avatarMediaId);
        if (avatarMediaId != null) {
            try {
                java.util.UUID.fromString(avatarMediaId);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("avatarMediaId must be a UUID");
            }
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

    /** 可接任务地区：与 tags 同口径归一化（trim/去重），上限放宽到城市数量级。 */
    private static List<String> normalizeRegions(List<String> regions) {
        if (regions == null) {
            return List.of();
        }
        List<String> cleaned = regions.stream()
                .filter(r -> r != null && !r.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (cleaned.size() > MAX_REGIONS) {
            throw new IllegalArgumentException("too many service regions");
        }
        return cleaned;
    }

    private static String trimToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
