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
        if (method != null && path != null) {
            for (RouteProperties route : properties.routes()) {
                if (route.enabled() && matches(route, method, path)) {
                    return properties.upstreams().get(route.upstream());
                }
            }
        }
        return properties.upstreams().get(properties.defaultUpstream());
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
        if (routePath.endsWith("/**")) {
            String prefix = routePath.substring(0, routePath.length() - 3);
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
        return path.equals(routePath) || path.startsWith(routePath + "/");
    }
}
