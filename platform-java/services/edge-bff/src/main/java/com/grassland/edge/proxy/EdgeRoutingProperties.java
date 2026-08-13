package com.grassland.edge.proxy;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "edge")
public record EdgeRoutingProperties(Map<String, URI> upstreams, List<RouteProperties> routes, String defaultUpstream) {
    public static final String FAIL_CLOSED = "fail-closed";

    public EdgeRoutingProperties {
        if (upstreams == null || upstreams.isEmpty()) {
            throw new IllegalArgumentException("edge.upstreams must define at least one upstream");
        }
        upstreams = new LinkedHashMap<>(upstreams);
        upstreams.forEach((name, uri) -> {
            if (uri == null || uri.getHost() == null) {
                throw new IllegalArgumentException("edge.upstreams." + name + " must include a host");
            }
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("edge.upstreams." + name + " must use http or https");
            }
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("edge.upstreams." + name + " must not include a query or fragment");
            }
        });
        if (defaultUpstream == null || defaultUpstream.isBlank()) {
            throw new IllegalArgumentException("edge.default-upstream must be set");
        }
        if (!FAIL_CLOSED.equals(defaultUpstream) && !upstreams.containsKey(defaultUpstream)) {
            throw new IllegalArgumentException("edge.default-upstream '" + defaultUpstream + "' is not defined in edge.upstreams");
        }
        routes = routes == null ? List.of() : routes;
        for (RouteProperties route : routes) {
            if (!upstreams.containsKey(route.upstream())) {
                throw new IllegalArgumentException("route upstream '" + route.upstream() + "' is not defined in edge.upstreams");
            }
        }
    }
}
