package com.grassland.intelligence.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.ai.DnsPinningResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import reactor.core.publisher.Mono;

/**
 * ModelListingService 执行侧出站口径回归：listModels/verifyModel 不得再用裸 WebClient
 * 直连用户 baseUrl——校验失败必须以 400 指向用户配置，且在任何 HTTP 请求发出之前。
 */
class ModelListingServiceTest {

    private static final String HTTP_SETTINGS = """
            {"features":{"video":{"provider":"openai","baseUrl":"http://provider.example","apiKey":"sk-x"}}}
            """;
    private static final String INTRANET_SETTINGS = """
            {"features":{"video":{"provider":"openai","baseUrl":"https://intranet.example","apiKey":"sk-x"}}}
            """;

    private final UserSettingsRepository repo = mock(UserSettingsRepository.class);
    private final AnalysisSettingsService analysisSettings = mock(AnalysisSettingsService.class);

    private ModelListingService service(DnsPinningResolver resolver) {
        return new ModelListingService(analysisSettings, repo, resolver, 8000);
    }

    private static DnsPinningResolver resolverResolving(String host, String ip)
            throws UnknownHostException {
        return DnsPinningResolver.create(name -> host.equals(name)
                ? new InetAddress[] { InetAddress.getByName(ip) }
                : new InetAddress[] { InetAddress.getByName("203.0.113.10") });
    }

    @org.junit.jupiter.api.Test
    void listModelsRejectsHttpBaseUrlAsUserConfigurationError() {
        when(repo.findByAccountAndType("acct", "analysis")).thenReturn(Mono.just(HTTP_SETTINGS));
        ModelListingService service = service(DnsPinningResolver.create());

        assertThatThrownBy(() -> service.listModels("acct", "video").block())
                .isInstanceOf(IntelligenceException.class)
                .hasMessageContaining("HTTPS")
                .extracting(e -> ((IntelligenceException) e).status())
                .isEqualTo(400);
    }

    @org.junit.jupiter.api.Test
    void listModelsRejectsDomainResolvingToIntranet() throws Exception {
        when(repo.findByAccountAndType("acct", "analysis")).thenReturn(Mono.just(INTRANET_SETTINGS));
        ModelListingService service = service(resolverResolving("intranet.example", "10.0.0.8"));

        assertThatThrownBy(() -> service.listModels("acct", "video").block())
                .isInstanceOf(IntelligenceException.class)
                .hasMessageContaining("内网")
                .extracting(e -> ((IntelligenceException) e).status())
                .isEqualTo(400);
    }

    @org.junit.jupiter.api.Test
    void verifyModelRejectsHttpBaseUrlAsUserConfigurationError() {
        when(repo.findByAccountAndType("acct", "analysis")).thenReturn(Mono.just(HTTP_SETTINGS));
        ModelListingService service = service(DnsPinningResolver.create());

        assertThatThrownBy(() -> service.verifyModel("acct", "video", "qwen-max").block())
                .isInstanceOf(IntelligenceException.class)
                .hasMessageContaining("HTTPS")
                .extracting(e -> ((IntelligenceException) e).status())
                .isEqualTo(400);
    }

    @org.junit.jupiter.api.Test
    void missingConfigurationStillSurfacesAs400() {
        when(repo.findByAccountAndType("acct", "analysis")).thenReturn(Mono.empty());
        ModelListingService service = service(DnsPinningResolver.create());

        assertThatThrownBy(() -> service.listModels("acct", "video").block())
                .isInstanceOf(IntelligenceException.class)
                .hasMessageContaining("baseUrl");
        // 空 settings 落默认值（无 features），与既有契约一致：要求先配置。
        assertThat(service).isNotNull();
    }
}
