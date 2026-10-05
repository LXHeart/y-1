package com.grassland.intelligence.digitalhuman;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Permanent retirement gate. History and draining operations still use their existing authorization. */
@Component
public final class DigitalHumanRetirementFilter implements WebFilter, Ordered {
    private static final Set<String> CREATION = Set.of("profiles", "preflights", "sessions", "voice-previews", "avatars");
    private static final Set<String> ACTIVE_SESSION = Set.of("resume", "heartbeat", "connection-grants",
            "webrtc/offer", "media-ready", "turns", "greeting", "recordings", "playback-reset");

    private static final List<PathPattern> POST_PATHS = Stream.concat(
            CREATION.stream().map(path -> "/api/digital-human/" + path),
            ACTIVE_SESSION.stream().map(action -> "/api/digital-human/sessions/{id}/" + action))
            .map(PathPatternParser.defaultInstance::parse).toList();

    static boolean retired(String method, String path) {
        // Use the same segment decoding/matrix-parameter rules as WebFlux routing.
        String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        PathContainer parsed = PathContainer.parsePath(normalized);
        if ("PUT".equals(method)) return PathPatternParser.defaultInstance
                .parse("/api/admin/digital-human/config").matches(parsed);
        if ("PATCH".equals(method)) return PathPatternParser.defaultInstance
                .parse("/api/digital-human/profiles/{id}").matches(parsed);
        return "POST".equals(method) && POST_PATHS.stream().anyMatch(pattern -> pattern.matches(parsed));
    }

    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!retired(exchange.getRequest().getMethod().name(), exchange.getRequest().getPath().value()))
            return chain.filter(exchange);
        exchange.getResponse().setStatusCode(HttpStatus.GONE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        byte[] body = "{\"success\":false,\"code\":\"dh_retired\",\"error\":\"实时数字人已退役，请使用视频创作。\"}".getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
