package com.grassland.intelligence.douyin;

import com.grassland.intelligence.mediaplatform.LocalMediaStreamer;
import com.grassland.intelligence.mediaplatform.PlatformMediaService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class DouyinAudioController {
    private final DouyinProxyToken tokens;
    private final PlatformMediaService media;
    private final LocalMediaStreamer streamer;

    public DouyinAudioController(DouyinProxyToken tokens, PlatformMediaService media, LocalMediaStreamer streamer) {
        this.tokens = tokens;
        this.media = media;
        this.streamer = streamer;
    }

    @GetMapping("/api/douyin/audio/{token}")
    public Mono<Void> download(@PathVariable String token, ServerWebExchange exchange) {
        return media.prepareDouyinAudio(tokens.parse(token)).flatMap(id -> {
            var artifact = media.artifact(id);
            String encoded = URLEncoder.encode(artifact.filename(), StandardCharsets.UTF_8).replace("+", "%20");
            return streamer.stream(artifact, exchange.getRequest().getHeaders().getFirst(HttpHeaders.RANGE),
                    "attachment; filename*=UTF-8''" + encoded, exchange.getResponse())
                    .doFinally(ignored -> media.remove(id));
        });
    }
}
