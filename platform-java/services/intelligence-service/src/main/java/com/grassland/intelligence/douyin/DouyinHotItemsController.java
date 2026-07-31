package com.grassland.intelligence.douyin;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 抖音热点（草场 Slice 13 Stage 1）：{@code GET /api/douyin/hot-items}。路径沿用 legacy，前端零改动。
 *
 * <p>公开端点，无 auth/credits；edge-bff exact 路由 {@code EDGE_ROUTE_DOUYIN_HOT_ITEMS_INTELLIGENCE}
 * 默认 false，关闭时回落 legacy Express（回滚安全）。
 */
@RestController
public class DouyinHotItemsController {

    private final DouyinHotItemsService service;

    public DouyinHotItemsController(DouyinHotItemsService service) {
        this.service = service;
    }

    @GetMapping("/api/douyin/hot-items")
    public Mono<Map<String, Object>> hotItems() {
        return service.load()
                .map(items -> Map.<String, Object>of("success", true, "data", Map.of("items", items)));
    }
}
