package com.grassland.marketplace.reputation;

/** Lv5 邀请更新；首次写入 expectedVersion=0。 */
public record UpdateLv5AdmissionRequest(Boolean admitted, Long expectedVersion, String note) {}
