package com.grassland.edge.proxy;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fail-closed checks for the media cutover flags.
 *
 * The Java media handlers need the shared proxy secret and a browser/model-reachable
 * public origin. Without these values a process can start successfully but every
 * extracted token or short-video analysis request will fail after traffic is cut.
 */
@Component
public final class MediaCutoverGuard {

    private static final int MIN_SECRET_LENGTH = 32;

    private final EdgeRoutingProperties routing;
    private final Environment environment;

    public MediaCutoverGuard(EdgeRoutingProperties routing, Environment environment) {
        this.routing = routing;
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        boolean bilibili = enabledForMedia("/api/bilibili/");
        boolean douyin = enabledForMedia("/api/douyin/");
        if (!bilibili && !douyin) {
            return;
        }

        requireSecret("Bilibili", "BILIBILI_PROXY_TOKEN_SECRET", bilibili);
        requireSecret("Douyin", "DOUYIN_PROXY_TOKEN_SECRET", douyin);

        String origin = environment.getProperty("PUBLIC_BACKEND_ORIGIN", "").trim();
        if (origin.isEmpty()) {
            throw new IllegalStateException("PUBLIC_BACKEND_ORIGIN is required when media cutover is enabled");
        }
        try {
            URI uri = URI.create(origin);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("PUBLIC_BACKEND_ORIGIN must be an absolute http(s) origin without query or fragment");
        }
        if (!routing.upstreams().containsKey("intelligence")) {
            throw new IllegalStateException("intelligence upstream is required when media cutover is enabled");
        }
    }

    private boolean enabledForMedia(String prefix) {
        return routing.routes().stream().anyMatch(route -> route.enabled()
                && "intelligence".equals(route.upstream())
                && route.path() != null && route.path().startsWith(prefix)
                && (route.path().endsWith("/extract-video") || route.path().endsWith("/analyze-video")
                    || route.path().endsWith("/proxy") || route.path().endsWith("/download")));
    }

    private void requireSecret(String platform, String key, boolean enabled) {
        if (!enabled) {
            return;
        }
        String value = environment.getProperty(key, "").trim();
        if (value.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(key + " must be at least " + MIN_SECRET_LENGTH
                    + " characters when " + platform + " media cutover is enabled");
        }
    }
}
