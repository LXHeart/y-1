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
                "store.StorePublicMediaController",
                "store.StorePublicProfileController");
        register(policies, TOKEN_AUTHENTICATED,
                "mobile.RefreshController",
                "mobile.RevokeController");
        register(policies, AUTHENTICATED,
                "auth.MeController",
                "identityprofile.IdentityAuditController",
                "identityprofile.IdentityProfileController",
                "identityprofile.IdentitySessionController",
                "identityprofile.ReauthenticationController",
                "invitation.MyInvitationController",
                "membership.MyOrganizationScopeController",
                "mobile.DeviceController",
                "notification.NotificationController",
                "notify.external.NotificationEndpointController",
                "organization.OrganizationController",
                "recommenderprofile.RecommenderProfileController",
                "store.MyStoreScopeController");
        register(policies, ORGANIZATION_SCOPED,
                "brand.BrandProfileController",
                "invitation.OrganizationInvitationController",
                "kyb.MerchantAttachmentController",
                "kyb.MerchantProfileController",
                "kyb.WithdrawalAccountController",
                "membership.MembershipController",
                "store.StoreController");
        register(policies, STORE_SCOPED,
                "store.StoreMediaController",
                "store.StoreMembershipController");
        register(policies, ADMIN,
                "admin.AdminUserController",
                "kyb.KybVerificationController");
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
