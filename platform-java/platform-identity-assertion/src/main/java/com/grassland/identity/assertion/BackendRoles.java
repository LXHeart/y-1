package com.grassland.identity.assertion;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 后台角色集合解析与判定（GL-P2-ADMIN-001 RBAC 地基）。
 *
 * <p>断言 {@code role} claim 以逗号分隔承载多值（如 {@code "platform_admin,content_reviewer"}）。
 * 本类把它解析为 {@link Set}{@code <}{@link BackendRole}{@code >}，并提供超集语义判定。
 *
 * <p>{@link BackendRole#PLATFORM_ADMIN} 是超集：持有它即视为持有所有角色
 * （{@link #hasAny} 对 PLATFORM_ADMIN 恒返回 true）——对齐现有 {@code admin} 超集语义。
 */
public final class BackendRoles {

    private BackendRoles() {}

    /**
     * 解析断言 role claim（逗号分隔 String）→ 有序 Set。null/空 → 空 Set。未知值被忽略（前向兼容）。
     */
    public static Set<BackendRole> fromClaim(String roleClaim) {
        Set<BackendRole> roles = new LinkedHashSet<>();
        if (roleClaim == null || roleClaim.isBlank()) {
            return roles;
        }
        Arrays.stream(roleClaim.split(","))
                .map(BackendRole::fromDb)
                .filter(java.util.Objects::nonNull)
                .forEach(roles::add);
        return roles;
    }

    /**
     * 是否持有任一所需角色（含超集语义）。{@code roleClaim} 含 PLATFORM_ADMIN → 恒 true。
     *
     * @param roleClaim 断言 role claim（逗号分隔）
     * @param required  所需角色（任一匹配即通过）
     */
    public static boolean hasAny(String roleClaim, BackendRole... required) {
        return hasAny(fromClaim(roleClaim), required);
    }

    /**
     * Set 版本的 {@link #hasAny(String, BackendRole...)}，避免「Set → join → 再 parse」的往返。
     * 持有 PLATFORM_ADMIN → 恒 true。
     */
    public static boolean hasAny(Set<BackendRole> held, BackendRole... required) {
        if (required == null || required.length == 0) {
            return false;
        }
        if (held.contains(BackendRole.PLATFORM_ADMIN)) {
            return true;
        }
        for (BackendRole role : required) {
            if (held.contains(role)) {
                return true;
            }
        }
        return false;
    }
}
