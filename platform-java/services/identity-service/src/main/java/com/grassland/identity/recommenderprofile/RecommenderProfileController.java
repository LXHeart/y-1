package com.grassland.identity.recommenderprofile;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.organization.CurrentAccountResolver;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * 推荐官画像 HTTP 入口（PRD 六）。
 *
 * <ul>
 *   <li>GET/PUT /api/me/recommender-profile — 推荐官自己维护（PUT 整份覆盖）。</li>
 *   <li>GET /api/recommenders/{accountId}/profile — 商家在审核报名时查看对方画像。</li>
 * </ul>
 *
 * <p><b>可见性</b>：画像是给商家做撮合判断用的，故任何登录用户都能按 accountId 读——
 * 但只回画像字段，<b>不回邮箱等账号信息</b>，也没有「按条件搜人」的入口（那会变成人肉数据库）。
 * 商家拿到 accountId 的唯一途径是对方主动报名了自己的任务。
 */
@RestController
public class RecommenderProfileController {

    private final CurrentAccountResolver accounts;
    private final RecommenderProfileRepository profiles;

    public RecommenderProfileController(CurrentAccountResolver accounts, RecommenderProfileRepository profiles) {
        this.accounts = accounts;
        this.profiles = profiles;
    }

    @GetMapping("/api/me/recommender-profile")
    public Mono<ResponseEntity<Map<String, Object>>> myProfile(ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(account -> profiles.findByAccount(account.id())
                        .defaultIfEmpty(RecommenderProfile.empty(account.id()))
                        .map(profile -> ok(toBody(profile))));
    }

    @PutMapping(value = "/api/me/recommender-profile", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> updateMyProfile(
            @RequestBody UpdateRecommenderProfileRequest body, ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(account -> profiles.upsert(account.id(), body).map(profile -> ok(toBody(profile))));
    }

    @GetMapping("/api/recommenders/{accountId}/profile")
    public Mono<ResponseEntity<Map<String, Object>>> profileOf(@PathVariable String accountId,
                                                               ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(viewer -> profiles.findByAccount(accountId)
                        // 没填过资料 → 空画像，而不是 404：对商家而言「这人没填」才是事实
                        .defaultIfEmpty(RecommenderProfile.empty(accountId))
                        .map(profile -> ok(toBody(profile))));
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

    private static Map<String, Object> toBody(RecommenderProfile profile) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("accountId", profile.accountId());
        map.put("displayName", profile.displayName());
        map.put("bio", profile.bio());
        map.put("contentTags", profile.contentTags());
        map.put("domainTags", profile.domainTags());
        map.put("socialAccounts", profile.socialAccounts().stream()
                .map(RecommenderProfileController::socialBody).toList());
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
}
