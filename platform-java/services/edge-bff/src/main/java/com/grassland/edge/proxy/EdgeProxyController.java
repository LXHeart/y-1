package com.grassland.edge.proxy;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class EdgeProxyController {
    private final RoutingProxyHandler handler;

    public EdgeProxyController(RoutingProxyHandler handler) {
        this.handler = handler;
    }

    @RequestMapping({"/api", "/api/**"})
    public Mono<Void> proxyApi(ServerWebExchange exchange) {
        return handler.proxy(exchange);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "edge-bff");
    }
}
