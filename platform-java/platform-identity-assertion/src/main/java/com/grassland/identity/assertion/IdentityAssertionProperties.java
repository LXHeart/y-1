package com.grassland.identity.assertion;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 内部身份断言配置（属性前缀 {@code identity-assertion}）。BFF（签发）与领域服务（验签）共用同一 secret+audience，
 * 否则签发/验签不通（验签失败会回退 cookie，降级而非宕机）。
 *
 * <p>仅当 {@code enabled=true} 时强校验 secret（仿 {@code ObjectStorageProperties} 的 compact-constructor fail-fast），
 * 未启用断言的服务（如 finance/trust 暂不消费）引依赖也不会启动失败。
 */
@ConfigurationProperties(prefix = "identity-assertion")
public record IdentityAssertionProperties(
        boolean enabled,
        String secret,
        long ttlSeconds,
        String audience,
        String headerName,
        long leewaySeconds,
        List<String> internalHeaderDenylist) {

    public IdentityAssertionProperties {
        if (ttlSeconds <= 0) {
            ttlSeconds = 60;
        }
        if (leewaySeconds < 0) {
            leewaySeconds = 5;
        }
        if (audience == null || audience.isBlank()) {
            audience = "grassland-internal";
        }
        if (headerName == null || headerName.isBlank()) {
            headerName = "X-Grassland-Identity";
        }
        internalHeaderDenylist = (internalHeaderDenylist == null || internalHeaderDenylist.isEmpty())
                ? List.of(
                        "X-Grassland-Identity",
                        "X-Grassland-Account-Id",
                        "X-Grassland-Active-Identity",
                        "X-Grassland-Session-Token")
                : List.copyOf(internalHeaderDenylist);
        if (enabled && (secret == null || secret.isBlank())) {
            throw new IllegalArgumentException(
                    "identity-assertion.secret must be set when identity-assertion.enabled=true");
        }
    }

    public Duration ttl() {
        return Duration.ofSeconds(ttlSeconds);
    }

    public Duration leeway() {
        return Duration.ofSeconds(leewaySeconds);
    }
}
