package com.grassland.intelligence.articleimage;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.List;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BingImageSearchClientTest {

    private static WireMockServer wireMock;
    private static BingImageSearchClient client;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        IntSupplier fixedOffset = () -> 7;
        client = new BingImageSearchClient(wireMock.baseUrl() + "/images/search", Duration.ofSeconds(2), fixedOffset);
    }

    @AfterAll
    static void stopServer() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetMappings();
    }

    @Test
    @DisplayName("parses Bing metadata, filters HTTP and de-duplicates URLs")
    void parsesMetadataResults() {
        wireMock.stubFor(get(urlPathEqualTo("/images/search")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html")
                .withBody("""
                        <div class="iusc" m='{"murl":"https://cdn.example/1.jpg","turl":"https://cdn.example/t1.jpg","purl":"https://source.example/1","desc":"第一张","w":1200,"h":800}'></div>
                        <div class="iusc" m='{"murl":"https://cdn.example/1.jpg","turl":"https://cdn.example/t2.jpg"}'></div>
                        <div class="iusc" m='{"murl":"http://unsafe.example/2.jpg","turl":"https://cdn.example/t2.jpg"}'></div>
                        """)));

        List<ImageSearchResult> results = client.search("职场沟通 插画", 3).block(Duration.ofSeconds(3));

        assertThat(results).containsExactly(new ImageSearchResult(
                "https://cdn.example/1.jpg", "https://cdn.example/t1.jpg",
                "https://source.example/1", "第一张", 1200, 800));
        wireMock.verify(getRequestedFor(urlPathEqualTo("/images/search")));
        assertThat(wireMock.getAllServeEvents().getFirst().getRequest().queryParameter("first").firstValue())
                .contains("7");
    }

    @Test
    @DisplayName("falls back to image elements when metadata is absent")
    void parsesFallbackImageElements() {
        wireMock.stubFor(get(urlPathEqualTo("/images/search")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html")
                .withBody("""
                        <div class="imgpt"><a href="https://source.example/x"><img src="https://cdn.example/x.jpg" alt="图片X" width="640" height="480"></a></div>
                        """)));

        assertThat(client.search("关键词", 1).block(Duration.ofSeconds(3)))
                .containsExactly(new ImageSearchResult(
                        "https://cdn.example/x.jpg", "https://cdn.example/x.jpg",
                        "https://source.example/x", "图片X", 640, 480));
    }

    @Test
    @DisplayName("empty pages map to compatible 502")
    void rejectsEmptySearchResult() {
        wireMock.stubFor(get(urlPathEqualTo("/images/search")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html").withBody("<html></html>")));

        assertThatThrownBy(() -> client.search("关键词", 3).block(Duration.ofSeconds(3)))
                .isInstanceOfSatisfying(IntelligenceException.class, error -> {
                    assertThat(error.status()).isEqualTo(502);
                    assertThat(error.getMessage()).contains("搜图失败");
                });
    }
}
