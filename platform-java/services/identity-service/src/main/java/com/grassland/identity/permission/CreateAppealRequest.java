package com.grassland.identity.permission;

import java.util.Map;

/**
 * 申诉请求体。草场身份域 Slice 2L（HLD D-05「申诉」）。
 *
 * <p>{@code materials} 为补充/更正后的材料（同 {@link CreatePermissionRequest#materials}，按 tier+行业校验）；
 * {@code note} 为申诉说明。仅原申请为 {@code rejected} 时可申诉。
 */
public record CreateAppealRequest(Map<String, String> materials, String note) {
}
