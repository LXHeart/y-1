package com.grassland.intelligence.ai.run;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 执行一次控制面 text run（POST /api/ai/runs）。
 *
 * <p>{@code allowFallback} 可空（默认 true）：无 BYOK 时是否回落平台模型（HLD §12.3 须显式授权）。
 * {@code maxTokens} 可空（默认 1024）。
 */
public record ExecuteRunRequest(
        @NotBlank(message = "capability 必填") String capability,
        @NotBlank(message = "prompt 必填") String prompt,
        @Positive @Max(8192) Integer maxTokens,
        Boolean allowFallback) {
}
