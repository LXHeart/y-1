package com.grassland.intelligence.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/**
 * {@link VerificationAnalysisService} provider guard 单元测试（草场 Slice 11 Verification Stage 3）。
 * provider 非 qwen → analyze 直接 400（部署配置错误信号），不经任何媒体/AI 路径。
 */
class VerificationAnalysisServiceTest {

    @Test
    @DisplayName("provider 非 qwen 时 analyze 返回 400（镜像 VideoRecreationAdaptationService provider guard）")
    void nonQwenProviderRejectedWith400() {
        Environment env = mock(Environment.class);
        when(env.getProperty("ai.verification.provider", "qwen")).thenReturn("coze");
        when(env.getProperty(eq("ai.verification.timeout-ms"), eq(Long.class), anyLong())).thenReturn(60000L);

        VerificationAnalysisService service = new VerificationAnalysisService(
                mock(AiCapabilityAdapter.class),
                mock(MediaReferenceRepository.class),
                mock(ObjectStorageAdapter.class),
                new VerificationResultNormalizer(),
                env);

        VerificationAnalysisRequest req =
                new VerificationAnalysisRequest(List.of(UUID.randomUUID()), "任务标题", null, "douyin");

        assertThatThrownBy(() -> service.analyze(req).block())
                .isInstanceOf(IntelligenceException.class)
                .satisfies(e -> assertThat(((IntelligenceException) e).status()).isEqualTo(400));
    }
}
