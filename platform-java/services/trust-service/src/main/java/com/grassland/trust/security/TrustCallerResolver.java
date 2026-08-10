package com.grassland.trust.security;

import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * trust 调用者解析（草场 Epic 6 Slice 6A / HLD 7.4、11.1）：**仅**信任 {@code X-Grassland-Identity} 断言，
 * 无 cookie 回退（trust 是纯下游）。复刻 finance 的 {@code FinanceCallerResolver}。
 *
 * <p>接受用户断言（merchant/recommender，OpenDispute/Decide；judge 投票；customer_service 客服终审）与 marketplace 服务断言
 * （principal=marketplace，开放争议查询）。{@link Caller#isMerchant()} 等 party/judge/cs 判定对 service callerKind 恒 false
 * （防服务断言冒充终端身份）。
 */
@Component
public class TrustCallerResolver {

    /** 受信任的 Saga 编排服务 principal（marketplace SettlementWindowWorkflow 查争议）。 */
    public static final String MARKETPLACE_SERVICE = "marketplace";

    private final IdentityAssertionSigner signer;
    private final String headerName;

    public TrustCallerResolver(IdentityAssertionSigner signer,
                               @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.signer = signer;
        this.headerName = headerName;
    }

    public Mono<Caller> resolve(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(headerName);
        if (header == null || header.isBlank()) {
            return Mono.error(new TrustException(401, "未登录"));
        }
        return signer.verifyReactive(header, Instant.now())
                .map(a -> new Caller(a.accountId(), a.activeIdentityType(), a.organizationId(),
                        a.callerKind(), a.principal(), a.reauthenticatedAt(), a.role()))
                .switchIfEmpty(Mono.error(new TrustException(401, "未登录")));
    }

    /** 仅终端商家用户（Decide 用）。 */
    public Mono<Caller> requireMerchant(ServerHttpRequest request) {
        return resolve(request)
                .filter(Caller::isMerchant)
                .switchIfEmpty(Mono.error(new TrustException(403, "需要商家身份")));
    }

    /** 商家或推荐官（OpenDispute / 启动审判：HLD 10.5 Party = 商家/推荐官）。 */
    public Mono<Caller> requireMerchantOrRecommender(ServerHttpRequest request) {
        return resolve(request)
                .filter(c -> c.isMerchant() || c.isRecommender())
                .switchIfEmpty(Mono.error(new TrustException(403, "需要商家或推荐官身份")));
    }

    /**
     * 审判官候选（投票门禁第一道：HLD 3.1「符合条件的推荐官」）。
     *
     * <p><b>语义变更（e2e 联调修正）</b>：原实现要求断言 {@code activeIdentityType=judge}，但 identity 的
     * {@code IdentityType} 只有 merchant/recommender——judge 身份<b>无法通过任何正常途径获得</b>，
     * 导致投票端点在真实链路恒 403（IT 直接 mint judge 断言才通过，掩盖了这个集成缺口）。
     *
     * <p>现改为：审判官 = 推荐官 + 已入 {@code judge} 池。此处只校验推荐官身份，
     * <b>入池校验由调用方查 {@code JudgeRepository} 完成</b>（避免 resolver 依赖 repo）。
     */
    public Mono<Caller> requireJudge(ServerHttpRequest request) {
        return resolve(request)
                .filter(Caller::isRecommender)
                .switchIfEmpty(Mono.error(new TrustException(403, "需要推荐官身份")));
    }

    /**
     * 客服（终审/覆盖判决：HLD §11.2 客服兜底）。
     *
     * <p><b>语义变更（e2e 联调修正，同 requireJudge 的问题）</b>：原实现要求断言
     * {@code activeIdentityType=customer_service}，但 identity 的 {@code IdentityType} 只有
     * merchant/recommender——客服身份无法通过任何正常途径获得，客服终审恒 403。
     *
     * <p>现改按<b>平台角色</b>判定（{@code app_users.role}）：客服是账号在平台侧的职能，
     * 与「当前 session 选了哪个业务视角」正交，不该建模为业务身份。
     * {@code admin} 视作客服超集（平台管理员可执行客服动作）。
     */
    public Mono<Caller> requireCustomerService(ServerHttpRequest request) {
        return resolve(request)
                .filter(Caller::isCustomerService)
                .switchIfEmpty(Mono.error(new TrustException(403, "需要客服身份")));
    }

    /**
     * 要求当前调用方持有任一指定后台角色（GL-P2-ADMIN-001，含 PLATFORM_ADMIN 超集语义）。
     * trust 只信 BFF 断言的 role claim；服务断言恒 false（防冒充）。
     */
    public Mono<Caller> requireRole(ServerHttpRequest request,
                                     com.grassland.identity.assertion.BackendRole... required) {
        return resolve(request)
                .filter(caller -> caller.hasBackendRole(required))
                .switchIfEmpty(Mono.error(new TrustException(403, "权限不足")));
    }

    /** 仅指定服务 principal 且组织匹配。供权威对账读取，终端用户不可调用。 */
    public Mono<Caller> requireServiceForOrg(
            ServerHttpRequest request, String organizationId, String servicePrincipal) {
        return resolve(request)
                .filter(c -> organizationId.equals(c.organizationId())
                        && c.isServicePrincipal(servicePrincipal))
                .switchIfEmpty(Mono.error(new TrustException(403, "无权读取内部争议终局")));
    }

    /** 接受终端商家或指定服务 principal（org 由调用方按已加载资源自查）。开放争议查询用（marketplace 调）。 */
    public Mono<Caller> resolveMerchantOrService(ServerHttpRequest request, String servicePrincipal) {
        return resolve(request)
                .filter(c -> c.isMerchant() || c.isServicePrincipal(servicePrincipal))
                .switchIfEmpty(Mono.error(new TrustException(403, "无权查询争议")));
    }

    /**
     * 接受当事方（商家/推荐官）、客服，或指定服务 principal（org 由调用方按已加载资源自查）。审判状态查询用。
     *
     * <p><b>客服（浏览器实测修正，与 requireJudge/requireCustomerService 同类问题的第三次出现）</b>：
     * 客服能执行终审（{@code requireCustomerService}），却读不到自己要覆盖的那份判决——
     * 客服既非 merchant 也非 recommender，在此被直接过滤掉，前端看板恒显示「无权查询争议」，
     * 连「客服终审」折叠区都渲染不出来，终审在 UI 上完全不可达。
     * 读（已脱敏的）快照严格弱于「覆盖判决」这一已授权的写动作，故一并放行。
     */
    public Mono<Caller> resolvePartyOrService(ServerHttpRequest request, String servicePrincipal) {
        return resolve(request)
                .filter(c -> c.isMerchant() || c.isRecommender()
                        || c.isCustomerService() || c.isServicePrincipal(servicePrincipal))
                .switchIfEmpty(Mono.error(new TrustException(403, "无权查询争议")));
    }

    /** 断言解析出的调用者。{@code callerKind}/{@code principal} 标识用户 vs 服务断言（HLD 11.1）；
     *  {@code reauthenticatedAt} 用于客服终审 MFA 近期性校验（HLD §11.2，可空=未再认证）。 */
    public record Caller(String accountId, String activeIdentityType, String organizationId,
                         String callerKind, String principal, Instant reauthenticatedAt,
                         String role) {
        public boolean isMerchant() {
            return !"service".equalsIgnoreCase(callerKind)
                    && "merchant".equalsIgnoreCase(activeIdentityType);
        }

        public boolean isRecommender() {
            return !"service".equalsIgnoreCase(callerKind)
                    && "recommender".equalsIgnoreCase(activeIdentityType);
        }

        // 注：原 isJudge()（按 activeIdentityType=judge 判定）已删除——judge 不是 identity 支持的身份类型，
        // 该方法永远返回 false。审判官现按「推荐官 + 已入 judge 池」判定，见 requireJudge + AdjudicationController。

        /**
         * 客服（HLD §11.2 终审）。按<b>平台角色</b>判定（{@code app_users.role}），非业务身份——
         * 见 {@code requireCustomerService} 注释。{@code admin} 为客服超集。
         * 服务断言不可冒充——callerKind=service 恒 false。
         */
        public boolean isCustomerService() {
            // GL-P2-ADMIN-001：backend_role 含 CUSTOMER_SERVICE 或 PLATFORM_ADMIN（超集）；旧值兜底
            return hasBackendRole(com.grassland.identity.assertion.BackendRole.CUSTOMER_SERVICE)
                    || (!isService() && ("customer_service".equalsIgnoreCase(role)
                            || "admin".equalsIgnoreCase(role)));
        }

        /**
         * 是否持有任一指定后台角色（含 PLATFORM_ADMIN 超集语义）。服务断言恒 false。
         */
        public boolean hasBackendRole(com.grassland.identity.assertion.BackendRole... required) {
            return !isService() && com.grassland.identity.assertion.BackendRoles.hasAny(role, required);
        }

        public boolean isService() {
            return "service".equalsIgnoreCase(callerKind);
        }

        public boolean isServicePrincipal(String expectedPrincipal) {
            return isService() && expectedPrincipal != null && expectedPrincipal.equalsIgnoreCase(principal);
        }
    }
}
