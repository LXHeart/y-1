package com.grassland.edge.proxy;

import java.net.URI;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 按 method + path 选择目标上游。先匹配 routes，未命中走 default-upstream。
 */
@Component
public class UpstreamResolver {
    private final EdgeRoutingProperties properties;

    public UpstreamResolver(EdgeRoutingProperties properties) {
        this.properties = properties;
    }

    public URI resolve(String method, String path) {
        return properties.upstreams().get(resolveUpstreamName(method, path));
    }

    /** 命中 route 的上游名；未命中走 default-upstream。供断言 filter 判定 legacy/内部。 */
    public String resolveUpstreamName(String method, String path) {
        if (method != null && path != null) {
            for (RouteProperties route : properties.routes()) {
                if (route.enabled() && matches(route, method, path)) {
                    return route.upstream();
                }
            }
        }
        return properties.defaultUpstream();
    }

    /** 内部 Java 上游 = 命中 route 的上游不是 legacy 默认上游。仅对这些上游签发断言（HLD 7.4「BFF → 内部服务」）。 */
    public boolean isInternalUpstream(String method, String path) {
        return !resolveUpstreamName(method, path).equals(properties.defaultUpstream());
    }

    boolean matches(RouteProperties route, String method, String path) {
        if (route.method() != null && !route.method().isBlank()
            && !route.method().equalsIgnoreCase(method)) {
            return false;
        }
        String routePath = route.path();
        if (routePath == null || routePath.isBlank()) {
            return true;
        }
        if (route.exact()) {
            return path.equals(routePath);
        }
        if (routePath.endsWith("/**")) {
            String prefix = routePath.substring(0, routePath.length() - 3);
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
        return path.equals(routePath) || path.startsWith(routePath + "/");
    }
}
