package com.grassland.edge.proxy;

import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * 透明流式代理：按 RouteManifest 选上游，零聚合透传 SSE/Multipart/Range。
 */
@Component
public final class RoutingProxyHandler {
    private final UpstreamResolver resolver;
    private final WebClient webClient;

    public RoutingProxyHandler(UpstreamResolver resolver) {
        this.resolver = resolver;
        HttpClient httpClient = HttpClient.create().compress(false).followRedirect(false);
        this.webClient = WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }

    public Mono<Void> proxy(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        String method = request.getMethod().name();
        String path = request.getURI().getPath();
        URI upstream = resolver.resolve(method, path);
        URI target = targetUri(upstream, request.getURI());
        HttpHeaders requestHeaders = LegacyProxyHeaderPolicy.requestHeaders(request.getHeaders());
        Flux<DataBuffer> requestBody = request.getBody();

        return webClient.method(request.getMethod())
            .uri(target)
            .headers(headers -> headers.addAll(requestHeaders))
            .body(BodyInserters.fromDataBuffers(requestBody))
            .exchangeToMono(response -> writeResponse(exchange, response));
    }

    private Mono<Void> writeResponse(ServerWebExchange exchange, ClientResponse response) {
        exchange.getResponse().setStatusCode(response.statusCode());
        HttpHeaders responseHeaders = LegacyProxyHeaderPolicy.responseHeaders(response.headers().asHttpHeaders());
        exchange.getResponse().getHeaders().putAll(responseHeaders);
        return exchange.getResponse().writeWith(response.bodyToFlux(DataBuffer.class));
    }

    private URI targetUri(URI upstream, URI incoming) {
        try {
            String basePath = upstream.getRawPath();
            String requestPath = incoming.getRawPath();
            String path = joinPaths(basePath, requestPath);
            return new URI(
                upstream.getScheme(),
                upstream.getRawAuthority(),
                path,
                incoming.getRawQuery(),
                null
            );
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Unable to construct upstream URI", error);
        }
    }

    private static String joinPaths(String basePath, String requestPath) {
        if (basePath == null || basePath.isEmpty() || "/".equals(basePath)) {
            return requestPath;
        }
        if (basePath.endsWith("/") && requestPath.startsWith("/")) {
            return basePath.substring(0, basePath.length() - 1) + requestPath;
        }
        if (!basePath.endsWith("/") && !requestPath.startsWith("/")) {
            return basePath + "/" + requestPath;
        }
        return basePath + requestPath;
    }
}
