package com.grassland.intelligence.homepage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.hottopic.HotTopicFilter;
import com.grassland.intelligence.hottopic.HotTopicTaxonomy;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class HomepageControllerTest {

    @Test
    void anonymousRequestBindsRepeatedAndCommaSeparatedFilters() {
        IntelligenceCallerResolver callers = mock(IntelligenceCallerResolver.class);
        HomepageHotService service = mock(HomepageHotService.class);
        when(callers.resolveOptional(any())).thenReturn(Mono.empty());
        when(service.loadHotItems(isNull(), any(HotTopicFilter.class))).thenReturn(Mono.just(
                HotItemsResult.of60s(List.of(), Instant.parse("2026-08-17T00:00:00Z"),
                        new HotTopicTaxonomy().metadata())));

        WebTestClient.bindToController(new HomepageController(callers, service)).build()
                .get()
                .uri(builder -> builder.path("/api/homepage/hot-items")
                        .queryParam("industry", "catering,retail")
                        .queryParam("industry", "beauty")
                        .queryParam("city", "上海")
                        .queryParam("contentType", "tech")
                        .queryParam("includeExpired", "true")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.provider").isEqualTo("60s")
                .jsonPath("$.data.taxonomy.version").isEqualTo("hot-taxonomy-v1");

        ArgumentCaptor<HotTopicFilter> filter = ArgumentCaptor.forClass(HotTopicFilter.class);
        verify(service).loadHotItems(isNull(), filter.capture());
        assertThat(filter.getValue().industries()).containsExactlyInAnyOrder("catering", "retail", "beauty");
        assertThat(filter.getValue().cities()).containsExactly("上海");
        assertThat(filter.getValue().contentTypes()).containsExactly("tech");
        assertThat(filter.getValue().includeExpired()).isTrue();
    }
}
