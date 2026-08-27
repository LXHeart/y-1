package com.grassland.identity.security;

import static com.grassland.identity.security.IdentityAccessLevel.ADMIN;
import static com.grassland.identity.security.IdentityAccessLevel.AUTHENTICATED;
import static com.grassland.identity.security.IdentityAccessLevel.BACKEND_ROLE;
import static com.grassland.identity.security.IdentityAccessLevel.ORGANIZATION_SCOPED;
import static com.grassland.identity.security.IdentityAccessLevel.PUBLIC;
import static com.grassland.identity.security.IdentityAccessLevel.SERVICE;
import static com.grassland.identity.security.IdentityAccessLevel.STORE_SCOPED;
import static com.grassland.identity.security.IdentityAccessLevel.TOKEN_AUTHENTICATED;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single inventory of the authorization contract for every identity controller.
 *
 * <p>Resource-level checks remain in {@code CurrentAccountResolver}, {@code OrgAuthorization} and
 * {@code StoreAuthorization}. This manifest makes the intended first gate explicit and lets the
 * build fail closed when a controller or a mixed-policy endpoint is added without review.
 */
public final class IdentityAuthorizationManifest {

    private static final String ROOT = "com.grassland.identity.";

    private static final Map<String, ControllerPolicy> CONTROLLERS = build();

    private IdentityAuthorizationManifest() {}

    public static Map<String, ControllerPolicy> controllers() {
        return CONTROLLERS;
    }

    private static Map<String, ControllerPolicy> build() {
        Map<String, ControllerPolicy> policies = new LinkedHashMap<>();

        register(policies, PUBLIC,
                "auth.CaptchaController",
                "auth.LoginController",
                "auth.LogoutController",
                "auth.RegisterController",
                "auth.SendCodeController",
                "brand.PublicBrandProfileController",
                "store.StorePublicMediaController",
                "store.StorePublicProfileController");
        register(policies, TOKEN_AUTHENTICATED,
                "mobile.RefreshController",
                "mobile.RevokeController");
        register(policies, AUTHENTICATED,
                "auth.MeController",
                // 任务书 #48：改密端点（登录态必需；首登强制改密形态免旧密由服务层判 account_flag）
                "auth.ChangePasswordController",
                "compliance.ComplianceController",
                "identityprofile.IdentityAuditController",
                "identityprofile.IdentityProfileController",
                "identityprofile.IdentitySessionController",
                "identityprofile.ReauthenticationController",
                "membership.MyOrganizationScopeController",
                "mobile.DeviceController",
                "notification.NotificationController",
                "notify.external.NotificationEndpointController",
                "organization.OrganizationController",
                "recommenderprofile.RecommenderProfileController",
                "store.MyStoreScopeController");
        register(policies, ORGANIZATION_SCOPED,
                "brand.BrandProfileController",
                "kyb.MerchantAttachmentController",
                "kyb.MerchantProfileController",
                "kyb.WithdrawalAccountController",
                "membership.MembershipController",
                "organization.subaccount.OrgSubAccountController",
                "store.StoreController");
        register(policies, STORE_SCOPED,
                "store.StoreMediaController",
                "store.StoreMembershipController");
        // 任务书 #48 子账号：createByOrg / 停用恢复 / 重置密码在 service 层做「操作者≥ADMIN 或
        // 纯门店经理」的资源级判定，清单口径登记为组织域；店长建员工走门店门禁，读开关仅需登录。
        policies.put(ROOT + "organization.subaccount.OrgSubAccountController", new ControllerPolicy(
                ORGANIZATION_SCOPED,
                Map.of(
                        "createByStoreManager", STORE_SCOPED,
                        "getReviewRequired", AUTHENTICATED)));
        register(policies, ADMIN,
                "admin.AdminUserController",
                "kyb.KybVerificationController");
        // 任务书 #48 子账号：createByOrg / 停用恢复 / 重置密码均在 service 层做「操作者≥ADMIN 或
        // 纯门店经理」的资源级判定，清单口径登记为组织域；店长建员工走门店门禁，读开关仅需登录。
        policies.put(ROOT + "organization.subaccount.OrgSubAccountController", new ControllerPolicy(
                ORGANIZATION_SCOPED,
                Map.of(
                        "createByStoreManager", STORE_SCOPED,
                        "getReviewRequired", AUTHENTICATED)));
        register(policies, SERVICE,
                "membership.InternalMembershipController",
                "membership.InternalOrgAuthorizationController",
                "store.InternalStoreAuthorizationController",
                "store.InternalStorePublicProfileController");

        policies.put(ROOT + "permission.PermissionRequestController", new ControllerPolicy(
                ORGANIZATION_SCOPED,
                Map.of(
                        "listPending", ADMIN,
                        "get", ADMIN,
                        "claim", ADMIN,
                        "audit", ADMIN,
                        "review", ADMIN)));
        // 主体更名（V40）：商家侧申请走组织角色鉴权（authz.requireRole），审核端点平台 admin
        policies.put(ROOT + "organization.OrganizationRenameAdminController", new ControllerPolicy(
                ORGANIZATION_SCOPED,
                Map.of(
                        "adminListPending", ADMIN,
                        "adminReview", ADMIN)));
        policies.put(ROOT + "recommenderprofile.RecommenderVerificationController", new ControllerPolicy(
                AUTHENTICATED,
                Map.of(
                        "listPending", BACKEND_ROLE,
                        "approve", BACKEND_ROLE,
                        "reject", BACKEND_ROLE)));

        return Map.copyOf(policies);
    }

    private static void register(Map<String, ControllerPolicy> policies, IdentityAccessLevel level,
                                 String... controllers) {
        for (String controller : controllers) {
            String className = ROOT + controller;
            if (policies.put(className, new ControllerPolicy(level, Map.of())) != null) {
                throw new IllegalStateException("duplicate identity authorization policy: " + className);
            }
        }
    }

    public record ControllerPolicy(
            IdentityAccessLevel defaultLevel,
            Map<String, IdentityAccessLevel> methodOverrides) {

        public ControllerPolicy {
            methodOverrides = Map.copyOf(methodOverrides);
        }

        public IdentityAccessLevel levelFor(String methodName) {
            return methodOverrides.getOrDefault(methodName, defaultLevel);
        }
    }
}
