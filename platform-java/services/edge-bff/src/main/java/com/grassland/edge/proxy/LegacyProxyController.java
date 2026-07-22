package com.grassland.edge.proxy;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Controller
public class LegacyProxyController {
    private final RoutingProxyHandler handler;

    public LegacyProxyController(RoutingProxyHandler handler) {
        this.handler = handler;
    }

    @RequestMapping({"/api", "/api/**"})
    public Mono<Void> proxyApi(ServerWebExchange exchange) {
        return handler.proxy(exchange);
    }

    @GetMapping("/health")
    public Mono<Void> proxyHealth(ServerWebExchange exchange) {
        return handler.proxy(exchange);
    }
}
