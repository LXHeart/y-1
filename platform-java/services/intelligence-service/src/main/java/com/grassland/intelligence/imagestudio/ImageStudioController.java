package com.grassland.intelligence.imagestudio;

import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 图片编辑台端点（任务书 #43）。
 *
 * <p>两个端点：
 * <ul>
 *   <li>{@code POST /api/image-studio/matting} — 抠图（入参 mediaId，返回短 TTL 读 URL）</li>
 *   <li>{@code GET /api/image-studio/matting-results/{id}} — 30min TTL PNG 读端点</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/image-studio")
public final class ImageStudioController {

    private final ImageStudioService service;

    public ImageStudioController(ImageStudioService service) {
        this.service = service;
    }

    @PostMapping("/matting")
    public Mono<Map<String, Object>> matting(
            @RequestBody MattingRequest body, ServerWebExchange exchange) {
        UUID mediaId = body == null ? null : body.mediaId();
        return service.matting(exchange.getRequest(), mediaId)
                .map(result -> Map.of("success", true, "data", Map.of("imageUrl", result.imageUrl())));
    }

    @GetMapping("/matting-results/{id}")
    public Mono<ResponseEntity<Resource>> mattingResult(@PathVariable String id) {
        if (!isCanonicalUuid(id)) {
            return Mono.error(new IntelligenceException(404, "抠图结果不存在"));
        }
        return service.findResult(id)
                .map(stored -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(30)).cachePrivate())
                        .body((Resource) new ByteArrayResource(stored.bytes())))
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "抠图结果不存在或已过期")));
    }

    private static boolean isCanonicalUuid(String id) {
        return id != null && id.matches(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    public record MattingRequest(UUID mediaId) {
        public MattingRequest {
            if (mediaId == null) {
                throw new IllegalArgumentException("mediaId 不能为空");
            }
        }
    }
}
