package com.grassland.marketplace.taskcatalog;

import java.time.Instant;

/** 商家对一次履约的评分（V6，PRD 五）。1-5 星 + 可选评语；一次履约至多一份（DB UNIQUE）。 */
public record EngagementRating(String id, String applicationId, String taskId,
                               String recommenderAccountId, String ratedByAccountId,
                               int score, String comment, Instant createdAt) {
}
