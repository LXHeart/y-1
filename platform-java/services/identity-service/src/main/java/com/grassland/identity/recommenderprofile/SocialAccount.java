package com.grassland.identity.recommenderprofile;

/**
 * 推荐官自报的社交账号（PRD 六「社交平台」）。
 *
 * <p>{@code followers} 是**自报**粉丝量，本轮不做平台核验——真核验要接各平台开放数据，
 * 属 PRD 九「自动核实引擎」的范畴。展示时须让商家知道这是自报值，不能当成平台数据。
 */
public record SocialAccount(String platform, String handle, Long followers) {

    public SocialAccount {
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("platform is required");
        }
        platform = platform.trim();
        if (handle != null) {
            handle = handle.isBlank() ? null : handle.trim();
        }
        if (followers != null && followers < 0) {
            throw new IllegalArgumentException("followers must be >= 0");
        }
    }
}
