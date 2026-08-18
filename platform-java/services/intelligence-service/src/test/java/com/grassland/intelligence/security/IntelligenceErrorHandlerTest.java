package com.grassland.intelligence.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class IntelligenceErrorHandlerTest {

    private final IntelligenceErrorHandler handler = new IntelligenceErrorHandler();

    @Test
    void codedExceptionAddsCodeToLegacyErrorEnvelope() {
        ResponseEntity<Map<String, Object>> response = handler.handle(
                new IntelligenceException(503, "unsupported_provider", "暂不支持该模型供应商"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(response.getBody()).containsEntry("code", "unsupported_provider");
        assertThat(response.getBody()).containsEntry("error", "暂不支持该模型供应商");
    }

    @Test
    void uncodedExceptionKeepsExistingEnvelopeShape() {
        ResponseEntity<Map<String, Object>> response = handler.handle(new IntelligenceException(400, "请求不合法"));

        assertThat(response.getBody())
                .hasSize(2)
                .containsEntry("success", false)
                .containsEntry("error", "请求不合法");
    }
}
