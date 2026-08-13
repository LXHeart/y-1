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
