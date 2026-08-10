package com.grassland.intelligence.douyin;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public class DouyinSessionController {
    private final DouyinBrowserService browser;
    public DouyinSessionController(DouyinBrowserService browser) { this.browser = browser; }

    @GetMapping({"/api/douyin/session", "/api/douyin/session/poll"})
    public Mono<Map<String, Object>> get(ServerWebExchange exchange) { return call(browser::snapshot, exchange); }

    @PostMapping("/api/douyin/session/start")
    public Mono<Map<String, Object>> start(ServerWebExchange exchange) { return call(browser::start, exchange); }

    @PostMapping("/api/douyin/session/logout")
    public Mono<Map<String, Object>> logout(ServerWebExchange exchange) { return call(browser::logout, exchange); }

    private Mono<Map<String, Object>> call(Supplier<Map<String, Object>> action, ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().setCacheControl(CacheControl.noStore());
        return Mono.fromCallable(action::get).subscribeOn(Schedulers.boundedElastic())
                .map(data -> Map.<String, Object>of("success", true, "data", data));
    }
}
