package com.grassland.identity.brand;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.auth.IdentityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * {@link BrandLogoMediaClient} fail-soft 包装单元测试（#32 D7）。上游 HTTP 映射
 * （200→URL / 404→empty / 5xx→503）在 intelligence MediaControllerIT 与客户端替身矩阵覆盖，
 * 这里只钉住 {@link BrandLogoMediaClient#logoUrlFailSoft} 的关键不变量：**任何异常都吞成 empty**，
 * 否则 GET 资料会因为 Logo 展示故障而 500。
 */
class BrandLogoMediaClientTest {

    @Test
    @DisplayName("logoUrlFailSoft 吞掉上游异常并完成空 Mono（GET 资料不因 Logo 故障 500）")
    void logoUrlFailSoftSwallowsUpstreamErrors() {
        BrandLogoMediaClient client = new BrandLogoMediaClient(
                null, "http://intelligence-service:8086", "X-Grassland-Identity", 100) {
            @Override
            public Mono<String> usableLogoUrl(String mediaId, String organizationId) {
                return Mono.error(new RuntimeException("upstream down"));
            }
        };

        assertThat(client.logoUrlFailSoft("media-1", "org-1").block()).isNull();
    }

    @Test
    @DisplayName("logoUrlFailSoft 原样透传成功 URL；404 的 empty 也保持 empty")
    void logoUrlFailSoftPassesThroughValues() {
        BrandLogoMediaClient passing = new BrandLogoMediaClient(
                null, "http://intelligence-service:8086", "X-Grassland-Identity", 100) {
            @Override
            public Mono<String> usableLogoUrl(String mediaId, String organizationId) {
                return Mono.just("https://cdn.example.com/brand-logo/" + mediaId);
            }
        };
        BrandLogoMediaClient missing = new BrandLogoMediaClient(
                null, "http://intelligence-service:8086", "X-Grassland-Identity", 100) {
            @Override
            public Mono<String> usableLogoUrl(String mediaId, String organizationId) {
                return Mono.empty();
            }
        };
        BrandLogoMediaClient unavailable = new BrandLogoMediaClient(
                null, "http://intelligence-service:8086", "X-Grassland-Identity", 100) {
            @Override
            public Mono<String> usableLogoUrl(String mediaId, String organizationId) {
                return Mono.error(new IdentityException(503, "品牌Logo服务暂不可用"));
            }
        };

        assertThat(passing.logoUrlFailSoft("media-1", "org-1").block())
                .isEqualTo("https://cdn.example.com/brand-logo/media-1");
        assertThat(missing.logoUrlFailSoft("media-1", "org-1").block()).isNull();
        assertThat(unavailable.logoUrlFailSoft("media-1", "org-1").block()).isNull();
    }
}
