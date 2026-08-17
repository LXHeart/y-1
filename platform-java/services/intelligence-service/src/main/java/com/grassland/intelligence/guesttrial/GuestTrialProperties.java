package com.grassland.intelligence.guesttrial;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 游客有限体验配置（任务书 #36 / ADR-D14）。全部带默认值（默认值即 ADR 决策），生产可收紧或整体关闭。
 *
 * <p>{@code capabilities} 是白名单目录（配置驱动，扩容不改代码面）；{@code dailyLimits} 可按 capability
 * 覆写日限额（缺省回落 {@code dailyLimitPerCapability}）。
 */
@ConfigurationProperties(prefix = "ai.guest-trial")
public class GuestTrialProperties {

    /** 总开关：false 时整个 trial 面禁用（404）。 */
    private boolean enabled = true;

    /** 体验目录白名单（capability 名）。 */
    private List<String> capabilities = List.of("article-titles", "content-score", "image-review");

    /** 每 capability 每日次数默认值（R2）。 */
    private int dailyLimitPerCapability = 3;

    /** 按 capability 覆写日限额（键=capability 名；缺省回落默认值）。 */
    private Map<String, Integer> dailyLimits = new LinkedHashMap<>();

    /** IP 日上限（所有 capability 合计；cookie 清除也刷不穿的兜底闸门）。 */
    private int ipDailyLimit = 30;

    /** IP 短窗限流（次/分钟，挡脚本重试）。 */
    private int ipBurstPerMinute = 10;

    /** gtid cookie 的 Secure 属性（生产经 env 置 true）。 */
    private boolean cookieSecure = false;

    /** Redis 限流键前缀。 */
    private String rateLimitKeyPrefix = "grassland:guest-trial:";

    /** 注册引导文案中的新用户赠送积分数（R7：读配置值，不硬编码）。 */
    private int signupBonusCredits = 50;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(List<String> capabilities) {
        this.capabilities = capabilities;
    }

    public int getDailyLimitPerCapability() {
        return dailyLimitPerCapability;
    }

    public void setDailyLimitPerCapability(int dailyLimitPerCapability) {
        this.dailyLimitPerCapability = dailyLimitPerCapability;
    }

    public Map<String, Integer> getDailyLimits() {
        return dailyLimits;
    }

    public void setDailyLimits(Map<String, Integer> dailyLimits) {
        this.dailyLimits = dailyLimits;
    }

    public int getIpDailyLimit() {
        return ipDailyLimit;
    }

    public void setIpDailyLimit(int ipDailyLimit) {
        this.ipDailyLimit = ipDailyLimit;
    }

    public int getIpBurstPerMinute() {
        return ipBurstPerMinute;
    }

    public void setIpBurstPerMinute(int ipBurstPerMinute) {
        this.ipBurstPerMinute = ipBurstPerMinute;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    public String getRateLimitKeyPrefix() {
        return rateLimitKeyPrefix;
    }

    public void setRateLimitKeyPrefix(String rateLimitKeyPrefix) {
        this.rateLimitKeyPrefix = rateLimitKeyPrefix;
    }

    public int getSignupBonusCredits() {
        return signupBonusCredits;
    }

    public void setSignupBonusCredits(int signupBonusCredits) {
        this.signupBonusCredits = signupBonusCredits;
    }

    /** 指定 capability 的有效日限额（覆写优先，缺省回落默认值）。 */
    public int limitFor(String capability) {
        return dailyLimits.getOrDefault(capability, dailyLimitPerCapability);
    }
}
