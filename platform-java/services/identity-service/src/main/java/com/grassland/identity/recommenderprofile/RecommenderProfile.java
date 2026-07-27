package com.grassland.identity.recommenderprofile;

import java.time.Instant;
import java.util.List;

/**
 * 推荐官画像（PRD 六：基础信息 / 社交平台 / 标签）。声誉指标不在这里——那些由 marketplace
 * 从履约事实派生（完成数、完成率、评分、响应时长），两者各自归属自己的服务。
 *
 * <p>标签与社交账号在库里分别是 {@code text[]} 与 {@code jsonb}，出到 API 时都是**真数组**，
 * 不是 JSON 字符串——本项目被「请求收对象、响应回字符串」坑过，这里不重犯。
 */
public record RecommenderProfile(
        String accountId,
        String displayName,
        String bio,
        List<String> contentTags,
        List<String> domainTags,
        List<SocialAccount> socialAccounts,
        Instant updatedAt
) {
    /** 从未维护过画像的账号：返回空画像而不是 404——对商家而言「这人没填资料」才是事实。 */
    public static RecommenderProfile empty(String accountId) {
        return new RecommenderProfile(accountId, null, null, List.of(), List.of(), List.of(), null);
    }
}
