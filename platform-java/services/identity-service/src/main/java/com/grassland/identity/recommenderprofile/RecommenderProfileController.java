package com.grassland.identity.recommenderprofile;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.identityprofile.IdentityProfileRepository;
import com.grassland.identity.identityprofile.IdentityType;
import com.grassland.identity.organization.CurrentAccountResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 推荐官画像 HTTP 入口（PRD 六；任务书 #29+#30 #29 扩资料字段 + 头像）。
 *
 * <ul>
 *   <li>GET/PUT /api/me/recommender-profile — 推荐官自己维护（PUT 整份覆盖）。</li>
 *   <li>GET /api/recommenders/{accountId}/profile — 商家在审核报名时查看对方画像。</li>
 * </ul>
 *
 * <p><b>可见性</b>：画像是给商家做撮合判断用的，故任何登录用户都能按 accountId 读——
 * 但只回画像字段，<b>不回邮箱等账号信息</b>，也没有「按条件搜人」的入口（那会变成人肉数据库）。
 * 商家拿到 accountId 的唯一途径是对方主动报名了自己的任务。收入统计/月度账单是私有数据（D7），
 * 不在本响应内，永不出现在公开端点。
 *
 * <p><b>头像（D6）</b>：PUT 时若带 {@code avatarMediaId}，先经 intelligence 复验（owner==account、
 * purpose=avatar、active）才落库；读取时把 {@code avatar_media_id} 换成短 TTL presigned GET
 * （字段 {@code avatarUrl}），公开端点不外泄 media id。换取是 IO，失败时优雅降级为 null，不阻断画像读取。
 */
@RestController
public class RecommenderProfileController {

    private static final Logger log = LoggerFactory.getLogger(RecommenderProfileController.class);

    private final CurrentAccountResolver accounts;
    private final RecommenderProfileRepository profiles;
    private final IdentityProfileRepository identities;
    private final RecommenderVerificationRepository verifications;
    private final AvatarMediaClient avatars;

    public RecommenderProfileController(
            CurrentAccountResolver accounts,
            RecommenderProfileRepository profiles,
            IdentityProfileRepository identities,
            RecommenderVerificationRepository verifications,
            AvatarMediaClient avatars) {
        this.accounts = accounts;
        this.profiles = profiles;
        this.identities = identities;
        this.verifications = verifications;
        this.avatars = avatars;
    }

    @GetMapping("/api/me/recommender-profile")
    public Mono<ResponseEntity<Map<String, Object>>> myProfile(ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(account -> profiles.findByAccount(account.id())
                        .defaultIfEmpty(RecommenderProfile.empty(account.id()))
                        .flatMap(profile -> verificationBody(account.id(), profile, true)));
    }

    @PutMapping(value = "/api/me/recommender-profile", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> updateMyProfile(
            @RequestBody UpdateRecommenderProfileRequest body, ServerHttpRequest request) {
        return accounts.resolve(request)
                // 推荐官画像是已开通推荐官身份的附属资料，不得把 profile 写入变成身份授予路径。
                .flatMap(account -> identities.findByAccountAndType(account.id(), IdentityType.RECOMMENDER.dbValue())
                        .switchIfEmpty(Mono.error(new IdentityException(409, "未开通推荐官身份，请先开通")))
                        .then(requireAvatarUsable(body, account.id()))
                        .then(profiles.upsert(account.id(), body))
                        .flatMap(profile -> verificationBody(account.id(), profile, true)));
    }

    @GetMapping("/api/recommenders/{accountId}/profile")
    public Mono<ResponseEntity<Map<String, Object>>> profileOf(@PathVariable String accountId,
                                                               ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(viewer -> profiles.findByAccount(accountId)
                        // 没填过资料 → 空画像，而不是 404：对商家而言「这人没填」才是事实
                        .defaultIfEmpty(RecommenderProfile.empty(accountId))
                        .flatMap(profile -> verificationBody(accountId, profile, false)));
    }

    /** PUT 带头像时先复验媒体归属/状态；不带头像直接放行（清空头像也合法）。 */
    private Mono<Void> requireAvatarUsable(UpdateRecommenderProfileRequest body, String accountId) {
        if (body.avatarMediaId() == null) {
            return Mono.empty();
        }
        return avatars.requireUsable(UUID.fromString(body.avatarMediaId()), accountId).then();
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    /** 标签超量/简介过长等由请求体的 compact constructor 抛出 → 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException error) {
        return ResponseEntity.status(400).body(Map.of("success", false, "error", "画像内容不合法"));
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    /**
     * 组装画像响应：先取 verification 状态，再换头像 presigned URL，最后包信封。
     * {@code includeMediaId} 为 true 时（self 读）回 {@code avatarMediaId} 供编辑表单回填；
     * 公开读只回 {@code avatarUrl}，不外泄 media id。
     */
    private Mono<ResponseEntity<Map<String, Object>>> verificationBody(
            String accountId, RecommenderProfile profile, boolean includeMediaId) {
        return verifications.findLatestByAccount(accountId)
                .map(request -> new Verification(request.status(), "approved".equalsIgnoreCase(request.status())))
                .defaultIfEmpty(new Verification("none", false))
                .flatMap(verification -> avatarUrl(profile)
                        .defaultIfEmpty("")
                        .map(url -> {
                            Map<String, Object> body = toBody(profile, url.isEmpty() ? null : url, includeMediaId);
                            body.put("verificationStatus", verification.status());
                            body.put("verified", verification.verified());
                            return ok(body);
                        }));
    }

    /** 头像 media id → 短 TTL presigned GET；无头像或换取失败均返回 empty（下游降级为 null，不阻断画像读取）。 */
    private Mono<String> avatarUrl(RecommenderProfile profile) {
        if (profile.avatarMediaId() == null) {
            return Mono.empty();
        }
        UUID mediaId;
        try {
            mediaId = UUID.fromString(profile.avatarMediaId());
        } catch (IllegalArgumentException e) {
            return Mono.empty();
        }
        return avatars.issueDownloadUrl(mediaId)
                .map(download -> download.downloadUrl().toString())
                .onErrorResume(error -> {
                    log.warn("avatar presigned url exchange failed: accountId={}, mediaId={}",
                            profile.accountId(), profile.avatarMediaId(), error);
                    return Mono.empty();
                });
    }

    private static Map<String, Object> toBody(RecommenderProfile profile, String avatarUrl, boolean includeMediaId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("accountId", profile.accountId());
        map.put("displayName", profile.displayName());
        map.put("bio", profile.bio());
        map.put("contentTags", profile.contentTags());
        map.put("domainTags", profile.domainTags());
        map.put("socialAccounts", profile.socialAccounts().stream()
                .map(RecommenderProfileController::socialBody).toList());
        map.put("residentCity", profile.residentCity());
        map.put("serviceRegions", profile.serviceRegions());
        map.put("contentPreferences", profile.contentPreferences());
        map.put("workSamples", profile.workSamples().stream()
                .map(RecommenderProfileController::workSampleBody).toList());
        map.put("avatarUrl", avatarUrl);
        if (includeMediaId) {
            map.put("avatarMediaId", profile.avatarMediaId());
        }
        map.put("updatedAt", profile.updatedAt() == null ? null : profile.updatedAt().toString());
        return map;
    }

    private static Map<String, Object> socialBody(SocialAccount account) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("platform", account.platform());
        map.put("handle", account.handle());
        map.put("followers", account.followers());
        return map;
    }

    private static Map<String, Object> workSampleBody(WorkSample sample) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("platform", sample.platform());
        map.put("title", sample.title());
        map.put("url", sample.url());
        return map;
    }

    private record Verification(String status, boolean verified) {}
}
