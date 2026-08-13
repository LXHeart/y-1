package com.grassland.intelligence.videoproduction;

import java.time.Duration;
import java.util.Locale;
import com.grassland.intelligence.ai.ProviderUrlGuard;
import jakarta.annotation.PostConstruct;
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
    private String webhookSecret;
    private Duration webhookTimestampWindow = Duration.ofMinutes(5);
    private String financeCreditsCentsPolicyVersion;
    private String financeCreditsCentsPolicyEffectiveAt;
    private String financeCreditsCentsPolicyRounding;
    private String financeCreditsCentsPolicyCentsNumerator;
    private String financeCreditsCentsPolicyCreditsDenominator;
    private String financeCreditsCentsPolicyMaxCentsPerOperation;

    /**
     * Validate deployment configuration before a real provider can receive a job.
     * Sandbox intentionally has no endpoint or secret; vendor modes must have a
     * public HTTPS/HTTP base URL, bounded paths and positive billing limits.
     */
    @PostConstruct
    void validate() {
        if (mode == null || mode.isBlank()) {
            throw new IllegalStateException("视频 provider mode 未配置");
        }
        String normalizedMode = mode.trim().toLowerCase(Locale.ROOT);
        if (!normalizedMode.equals("sandbox")
                && !normalizedMode.equals("seedance")
                && !normalizedMode.equals("minimax")) {
            throw new IllegalStateException("不支持的视频 provider: " + mode);
        }
        if (defaultDurationSeconds < 1 || maxDurationSeconds < defaultDurationSeconds
                || maxDurationSeconds > 300 || unitPriceCents < 1
                || pricingVersion == null || pricingVersion.isBlank()
                || platformModelVersion < 1 || maxConcurrency < 1 || batchSize < 1
                || maxAttempts < 1 || requestTimeout == null || requestTimeout.isZero()
                || requestTimeout.isNegative() || pollInterval == null || pollInterval.isZero()
                || pollInterval.isNegative()) {
            throw new IllegalStateException("视频 provider 的时长、计价、并发或超时配置非法");
        }
        if (!normalizedMode.equals("sandbox")) {
            if (!available()) {
                throw new IllegalStateException(unavailableReason());
            }
            if (!present(webhookSecret) || webhookSecret.length() < 32) {
                throw new IllegalStateException("视频 provider webhook secret 必须至少 32 字符");
            }
            ProviderUrlGuard.validate(baseUrl);
            validatePath(resolvedCreatePath(), "createPath");
            validatePath(resolvedPollPath(), "pollPath");
            validatePath(retrievePath, "retrievePath");
        }
    }

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

    private static void validatePath(String path, String name) {
        if (!present(path) || !path.startsWith("/") || path.startsWith("//")
                || path.contains("\\") || path.contains("?") || path.contains("#")
                || path.contains("://")) {
            throw new IllegalStateException("视频 provider 的 " + name + " 必须是相对 HTTP 路径");
        }
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
    public Duration getWebhookTimestampWindow() { return webhookTimestampWindow; }
    public void setWebhookTimestampWindow(Duration value) { this.webhookTimestampWindow = value; }
    public String getWebhookSecret(String provider) {
        return webhookSecret;
    }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String value) { this.webhookSecret = value; }
    public String getFinanceCreditsCentsPolicyVersion() { return financeCreditsCentsPolicyVersion; }
    public void setFinanceCreditsCentsPolicyVersion(String value) { this.financeCreditsCentsPolicyVersion = value; }
    public String getFinanceCreditsCentsPolicyEffectiveAt() { return financeCreditsCentsPolicyEffectiveAt; }
    public void setFinanceCreditsCentsPolicyEffectiveAt(String value) { this.financeCreditsCentsPolicyEffectiveAt = value; }
    public String getFinanceCreditsCentsPolicyRounding() { return financeCreditsCentsPolicyRounding; }
    public void setFinanceCreditsCentsPolicyRounding(String value) { this.financeCreditsCentsPolicyRounding = value; }
    public String getFinanceCreditsCentsPolicyCentsNumerator() { return financeCreditsCentsPolicyCentsNumerator; }
    public void setFinanceCreditsCentsPolicyCentsNumerator(String value) { this.financeCreditsCentsPolicyCentsNumerator = value; }
    public String getFinanceCreditsCentsPolicyCreditsDenominator() { return financeCreditsCentsPolicyCreditsDenominator; }
    public void setFinanceCreditsCentsPolicyCreditsDenominator(String value) { this.financeCreditsCentsPolicyCreditsDenominator = value; }
    public String getFinanceCreditsCentsPolicyMaxCentsPerOperation() { return financeCreditsCentsPolicyMaxCentsPerOperation; }
    public void setFinanceCreditsCentsPolicyMaxCentsPerOperation(String value) { this.financeCreditsCentsPolicyMaxCentsPerOperation = value; }

    /** Returns the approved policy version only when every policy field is structurally valid. */
    public String financeCreditsCentsPolicyState() {
        if (!present(financeCreditsCentsPolicyVersion)
                || !financeCreditsCentsPolicyVersion.matches("[A-Za-z0-9._-]{1,64}")
                || !present(financeCreditsCentsPolicyEffectiveAt)
                || !financeCreditsCentsPolicyEffectiveAt.matches(
                        "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")
                || !java.util.Set.of("HALF_UP", "HALF_EVEN", "DOWN", "UP")
                        .contains(financeCreditsCentsPolicyRounding)
                || !positiveInteger(financeCreditsCentsPolicyCentsNumerator)
                || !positiveInteger(financeCreditsCentsPolicyCreditsDenominator)
                || !positiveInteger(financeCreditsCentsPolicyMaxCentsPerOperation)) {
            return "policy_missing";
        }
        return financeCreditsCentsPolicyVersion;
    }

    private static boolean positiveInteger(String value) {
        return value != null && value.matches("[1-9][0-9]*");
    }
}
