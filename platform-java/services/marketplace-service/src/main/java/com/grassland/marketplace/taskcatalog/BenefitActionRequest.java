package com.grassland.marketplace.taskcatalog;

import java.time.Instant;
import java.util.List;

/**
 * 体验权益动作请求体（任务书 #96 §6 POST /benefit）：{@code action} = book/fulfill/confirm_fulfillment/
 * cancel/respond_default；book 携带 {@code items}（体验项目）与 {@code bookingWindow}（预约时段，ISO8601）。
 * 全字段可空包装类型（Jackson 缺字段不炸，house rule）。
 */
public record BenefitActionRequest(String action, List<String> items, Instant bookingWindow) {
}
