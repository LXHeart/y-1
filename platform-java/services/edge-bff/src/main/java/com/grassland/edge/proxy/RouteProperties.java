package com.grassland.edge.proxy;

public record RouteProperties(String method, String path, String upstream, boolean enabled) {
    public RouteProperties {
        if (upstream == null || upstream.isBlank()) {
            throw new IllegalArgumentException("route.upstream must be set");
        }
        if (enabled) {
            // default true
        }
    }

    public boolean enabled() {
        return enabled;
    }
}
