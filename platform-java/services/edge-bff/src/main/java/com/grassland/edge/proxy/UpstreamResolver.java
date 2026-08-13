package com.grassland.edge.proxy;

import java.net.URI;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 按 method + path 选择目标上游。先匹配 routes；生产默认策略为 fail-closed。
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

    /** 命中 route 的上游名；未命中返回默认策略。供断言 filter 与代理 handler 判定。 */
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

    /** 仅对已声明的 Java 上游签发内部断言；fail-closed 不签发。 */
    public boolean isInternalUpstream(String method, String path) {
        String upstream = resolveUpstreamName(method, path);
        return properties.upstreams().containsKey(upstream);
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
