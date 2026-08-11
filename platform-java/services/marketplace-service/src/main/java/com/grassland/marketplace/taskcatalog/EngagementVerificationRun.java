package com.grassland.marketplace.taskcatalog;

import java.time.Instant;

public record EngagementVerificationRun(
        String id, String submissionId, int runNumber, String engineVersion, String status,
        String taskContextJson, String evidenceJson, String checksJson,
        String triggeredBy, Instant createdAt) {}
