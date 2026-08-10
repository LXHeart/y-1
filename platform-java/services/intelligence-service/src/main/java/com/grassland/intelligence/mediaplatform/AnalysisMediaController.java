package com.grassland.intelligence.mediaplatform;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class AnalysisMediaController {
    private final PlatformMediaService media;
    private final LocalMediaStreamer streamer;

    public AnalysisMediaController(PlatformMediaService media, LocalMediaStreamer streamer) {
        this.media = media;
        this.streamer = streamer;
    }

    @GetMapping({"/api/bilibili/analysis-media/{id}", "/api/douyin/analysis-media/{id}"})
    public Mono<Void> serve(@PathVariable String id, ServerWebExchange exchange) {
        return streamer.stream(media.artifact(id), exchange.getRequest().getHeaders().getFirst(HttpHeaders.RANGE),
                null, exchange.getResponse());
    }
}
