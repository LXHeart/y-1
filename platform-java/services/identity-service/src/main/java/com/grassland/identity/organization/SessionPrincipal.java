package com.grassland.identity.organization;

import com.grassland.identity.user.AuthUser;

/**
 * 已鉴权会话主体：账号 + session token（sid）。草场身份域 Slice 2I（HLD D-08 per-session）。
 *
 * <p>由 {@link CurrentAccountResolver#resolvePrincipal} 产出，供需要 sid 的端点（活动身份 per-session、多设备）使用。
 */
public record SessionPrincipal(AuthUser user, String sid) {}
