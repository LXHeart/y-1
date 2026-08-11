package com.grassland.intelligence.videoproduction;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Runtime-selectable video provider and frozen billing defaults. */
@Component
@ConfigurationProperties(prefix = "ai.video-generation")
public class VideoGenerationProperties {

    private String mode = "sandbox";
    private String baseUrl;
    private String apiKey;
    private String model = "sandbox-video-v1";
    private String createPath;
    private String pollPath;
    private String retrievePath = "/v1/files/retrieve";
    private int defaultDurationSeconds = 5;
    private int maxDurationSeconds = 10;
    private int unitPriceCents = 1;
    private String pricingVersion = "video-config-v1";
    private int platformModelVersion = 1;
    private Duration requestTimeout = Duration.ofSeconds(30);
    private Duration pollInterval = Duration.ofSeconds(3);
    private Duration claimLease = Duration.ofMinutes(1);
    private int batchSize = 8;
    private int maxConcurrency = 4;
    private int maxAttempts = 60;
    private boolean workerEnabled = true;

    public boolean available() {
        if ("sandbox".equalsIgnoreCase(mode)) {
            return true;
        }
        return ("seedance".equalsIgnoreCase(mode) || "minimax".equalsIgnoreCase(mode))
                && present(baseUrl) && present(apiKey) && present(model) && unitPriceCents > 0;
    }

    public String unavailableReason() {
        if (!"sandbox".equalsIgnoreCase(mode)
                && !"seedance".equalsIgnoreCase(mode)
                && !"minimax".equalsIgnoreCase(mode)) {
            return "不支持的视频 provider: " + mode;
        }
        return available() ? null : "视频 provider 的 baseUrl、apiKey、model 或单秒价格未配置完整";
    }

    public String resolvedCreatePath() {
        if (present(createPath)) return createPath;
        return "minimax".equalsIgnoreCase(mode)
                ? "/v1/video_generation" : "/api/v3/contents/generations/tasks";
    }

    public String resolvedPollPath() {
        if (present(pollPath)) return pollPath;
        return "minimax".equalsIgnoreCase(mode)
                ? "/v1/query/video_generation" : "/api/v3/contents/generations/tasks/{taskId}";
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getCreatePath() { return createPath; }
    public void setCreatePath(String createPath) { this.createPath = createPath; }
    public String getPollPath() { return pollPath; }
    public void setPollPath(String pollPath) { this.pollPath = pollPath; }
    public String getRetrievePath() { return retrievePath; }
    public void setRetrievePath(String retrievePath) { this.retrievePath = retrievePath; }
    public int getDefaultDurationSeconds() { return defaultDurationSeconds; }
    public void setDefaultDurationSeconds(int value) { this.defaultDurationSeconds = value; }
    public int getMaxDurationSeconds() { return maxDurationSeconds; }
    public void setMaxDurationSeconds(int value) { this.maxDurationSeconds = value; }
    public int getUnitPriceCents() { return unitPriceCents; }
    public void setUnitPriceCents(int value) { this.unitPriceCents = value; }
    public String getPricingVersion() { return pricingVersion; }
    public void setPricingVersion(String value) { this.pricingVersion = value; }
    public int getPlatformModelVersion() { return platformModelVersion; }
    public void setPlatformModelVersion(int value) { this.platformModelVersion = value; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration value) { this.requestTimeout = value; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration value) { this.pollInterval = value; }
    public Duration getClaimLease() { return claimLease; }
    public void setClaimLease(Duration value) { this.claimLease = value; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) { this.batchSize = value; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int value) { this.maxConcurrency = value; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int value) { this.maxAttempts = value; }
    public boolean isWorkerEnabled() { return workerEnabled; }
    public void setWorkerEnabled(boolean value) { this.workerEnabled = value; }
}
