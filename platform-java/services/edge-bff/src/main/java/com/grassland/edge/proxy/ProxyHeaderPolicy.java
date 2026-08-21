package com.grassland.edge.proxy;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;

public final class ProxyHeaderPolicy {
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade"
    );

    private ProxyHeaderPolicy() {}

    public static HttpHeaders requestHeaders(HttpHeaders source) {
        return copyEndToEndHeaders(source, true);
    }

    /**
     * 会话/设备指纹用 {@code X-Forwarded-For}（identity 取最右一段）。经 Nginx 前置时链路完整透传；
     * 直连 edge（开发/vite 代理）时 XFF 缺失，identity 只能记到 edge 容器 IP——此处在 XFF 缺失时
     * 补写直接对端地址（edge 是受控代理，追加点可信）。已有 XFF 时原样透传，不改变生产单可信代理链。
     */
    public static HttpHeaders requestHeaders(HttpHeaders source, java.net.InetSocketAddress peer) {
        HttpHeaders headers = copyEndToEndHeaders(source, true);
        String forwarded = headers.getFirst("X-Forwarded-For");
        if ((forwarded == null || forwarded.isBlank()) && peer != null
                && peer.getAddress() != null) {
            headers.set("X-Forwarded-For", peer.getAddress().getHostAddress());
        }
        return headers;
    }

    public static HttpHeaders responseHeaders(HttpHeaders source) {
        return copyEndToEndHeaders(source, false);
    }

    private static HttpHeaders copyEndToEndHeaders(HttpHeaders source, boolean request) {
        Set<String> excluded = excludedHeaders(source);
        if (request) {
            excluded.add("host");
        }

        HttpHeaders result = new HttpHeaders();
        source.forEach((name, values) -> {
            if (!excluded.contains(name.toLowerCase(Locale.ROOT))) {
                result.put(name, values);
            }
        });
        return result;
    }

    private static Set<String> excludedHeaders(HttpHeaders source) {
        Set<String> excluded = new HashSet<>(HOP_BY_HOP_HEADERS);
        source.getOrEmpty(HttpHeaders.CONNECTION).stream()
            .flatMap(value -> Arrays.stream(value.split(",")))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(value -> value.toLowerCase(Locale.ROOT))
            .forEach(excluded::add);
        return excluded;
    }
}
