package com.grassland.identity.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * admin 列表端点统一分页信封（任务书 #2）。
 *
 * <p>请求参数口径（全部 admin 列表端点一致）：
 * <ul>
 * <li>{@code limit}：默认 50；非法（{@code null}/负数/0）回落默认值；上限钳到 200。</li>
 * <li>{@code offset}：默认 0；负数归 0。</li>
 * </ul>
 *
 * <p>响应口径：{@code data = {items, total, limit, offset}}，limit/offset 回显
 * <b>钳制后</b>的值；构造一律 {@link LinkedHashMap}（items/total 之外未来可能追加可空字段，
 * {@code Map.of} 遇 null 会 NPE）。
 */
public final class PageEnvelope {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private PageEnvelope() {
    }

    /** limit 钳制：{@code null}/≤0 → 默认 50；>200 → 200。 */
    public static int limit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /** offset 钳制：{@code null}/负数 → 0。 */
    public static int offset(Integer offset) {
        return offset == null || offset < 0 ? 0 : offset;
    }

    /** 统一信封体：{items, total, limit, offset}（LinkedHashMap，键序稳定）。 */
    public static Map<String, Object> data(List<?> items, long total, int limit, int offset) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("total", total);
        data.put("limit", limit);
        data.put("offset", offset);
        return data;
    }
}
