package com.grassland.edge.proxy;

/** 单条路由；{@code exact=true} 时 path 仅做全等匹配，否则保持既有前缀语义。 */
public final class RouteProperties {

    private String method;
    private String path;
    private String upstream;
    private boolean enabled;
    private boolean exact;

    public RouteProperties() {}

    public RouteProperties(String method, String path, String upstream, boolean enabled) {
        this(method, path, upstream, enabled, false);
    }

    public RouteProperties(String method, String path, String upstream, boolean enabled, boolean exact) {
        this.method = method;
        this.path = path;
        this.upstream = upstream;
        this.enabled = enabled;
        this.exact = exact;
        validate();
    }

    public String method() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String path() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String upstream() {
        return upstream;
    }

    public void setUpstream(String upstream) {
        this.upstream = upstream;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean exact() {
        return exact;
    }

    public void setExact(boolean exact) {
        this.exact = exact;
    }

    private void validate() {
        if (upstream == null || upstream.isBlank()) {
            throw new IllegalArgumentException("route.upstream must be set");
        }
    }
}
