package com.grassland.marketplace.ops;

import com.grassland.marketplace.security.MarketplaceException;

/**
 * 处置单流转请求基础形（GL-P1-OPS-001 Stage 1）：{@code expectedVersion} 乐观锁 + 可选备注。
 *
 * <p>{@code expectedVersion} <b>必填</b>：省略即「无条件覆盖」，两名运营并发处置同一单时后写会静默盖掉前写。
 */
public record OpsCaseActionRequest(Long expectedVersion, String note) {

    public long requireExpectedVersion() {
        return OpsCaseActionRequest.require(expectedVersion);
    }

    /** 缺失/非正 → 400（不默默取 1：那等于放弃乐观锁）。 */
    static long require(Long expectedVersion) {
        if (expectedVersion == null || expectedVersion <= 0) {
            throw new MarketplaceException(400, "expectedVersion 必填");
        }
        return expectedVersion;
    }
}
