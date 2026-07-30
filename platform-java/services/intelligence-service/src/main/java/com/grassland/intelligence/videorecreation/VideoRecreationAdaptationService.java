package com.grassland.intelligence.videorecreation;

import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** 视频内容改编 provider 编排；Java 迁移阶段仅使用平台级 Qwen 配置。 */
@Service
public class VideoRecreationAdaptationService {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);
    private static final String UNSUPPORTED = "当前视频分析服务不支持内容改编，请切换到 Qwen 后重试";

    private final AiCapabilityAdapter ai;
    private final VideoRecreationAdaptationResultNormalizer normalizer;
    private final Duration timeout;
    private final String provider;

    public VideoRecreationAdaptationService(
            AiCapabilityAdapter ai, VideoRecreationAdaptationResultNormalizer normalizer, Environment environment) {
        this.ai = ai;
        this.normalizer = normalizer;
        this.provider = environment.getProperty("ai.video-recreation.provider", "qwen");
        long timeoutMs = environment.getProperty(
                "ai.video-recreation.timeout-ms", Long.class, DEFAULT_TIMEOUT.toMillis());
        this.timeout = Duration.ofMillis(Math.max(1, Math.min(timeoutMs, 600_000)));
    }

    public Mono<Map<String, Object>> adapt(VideoRecreationAdaptationRequest request) {
        if (!"qwen".equalsIgnoreCase(provider)) {
            return Mono.error(new IntelligenceException(400, UNSUPPORTED));
        }
        String prompt = VideoRecreationAdaptationPrompts.build(request);
        List<ContentPart> parts = new ArrayList<>(request.referenceImages());
        parts.add(ContentPart.text(prompt));
        return ai.completeMultimodal(parts, timeout)
                .map(content -> normalizer.normalize(content, null));
    }
}
