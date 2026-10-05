package com.grassland.intelligence.creationassistant;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class CreationDraftHandoffController {
    private final IntelligenceCallerResolver callers;
    private final CreationDraftHandoffService handoffs;
    public CreationDraftHandoffController(IntelligenceCallerResolver callers, CreationDraftHandoffService handoffs) {
        this.callers = callers;
        this.handoffs = handoffs;
    }
    @PostMapping("/api/creation-drafts/{id}/handoffs")
    public Mono<ResponseEntity<Map<String, Object>>> create(@PathVariable String id,
            @RequestBody CreationDraftHandoffService.Command command, ServerWebExchange exchange) {
        return callers.requireUser(exchange.getRequest()).flatMap(caller -> handoffs.create(id, caller, command))
                .map(result -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .body(Map.of("success", true, "data", result.toMap())));
    }
}
