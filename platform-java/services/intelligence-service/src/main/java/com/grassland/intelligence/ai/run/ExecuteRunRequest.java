package com.grassland.intelligence.ai.run;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * 执行一次控制面 text run（POST /api/ai/runs）。
 *
 * <p>{@code allowFallback} 可空（默认 false）：无 BYOK 时是否回落平台模型（HLD §12.3 须显式授权）。
 * {@code maxTokens} 可空（默认 1024）。
 */
public record ExecuteRunRequest(
        @NotBlank(message = "capability 必填")
        @Pattern(regexp = "text", message = "当前同步入口仅支持 text capability") String capability,
        @NotBlank(message = "prompt 必填")
        @Size(max = 50000, message = "prompt 最多 50000 字符") String prompt,
        @Positive @Max(8192) Integer maxTokens,
        Boolean allowFallback,
        UUID contextSnapshotId) {

    public ExecuteRunRequest(String capability, String prompt, Integer maxTokens, Boolean allowFallback) {
        this(capability, prompt, maxTokens, allowFallback, null);
    }
}
