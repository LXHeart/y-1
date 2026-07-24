package com.grassland.marketplace.taskcatalog;

/**
 * 报名请求体（草场 Epic 4 Slice 4B）。仅 {@code note} 可选附言；recommender/taskId 由路径与断言决定（非请求体）。
 * 请求体可整体缺省（{@code @RequestBody(required=false)}）。
 */
public record ApplyRequest(String note) {
}
