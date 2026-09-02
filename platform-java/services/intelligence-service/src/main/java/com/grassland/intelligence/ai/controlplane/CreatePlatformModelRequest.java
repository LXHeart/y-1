package com.grassland.intelligence.ai.controlplane;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;

/**
 * 创建平台模型配置（POST /api/admin/ai/models，admin）。
 *
 * <p>{@code capability}/{@code modelRole} 是自然键：同一 (capability, model_role) 已存在 → 409。
 * {@code healthStatus} 可空，默认 healthy。
 *
 * <p><b>凭据两种给法</b>（{@link PlatformModelConfigController} 归一）：
 * <ul>
 *   <li>推荐：只给 {@code credentialId}，{@code provider}/{@code baseUrl} 留空由该凭据带出——
 *       治理台表单走这条，admin 不必手抄地址（运行时本就是
 *       {@code COALESCE(credential.base_url, config.base_url)}，凭据地址优先）。</li>
 *   <li>兼容：给 {@code provider}+{@code baseUrl} 不给 {@code credentialId}，按 (provider, baseUrl)
 *       反查凭据，查不到则隐式建一条无密钥凭据（既有行为，未改）。</li>
 * </ul>
 * 两者都不给 → 400。同时给且矛盾 → 以 {@code credentialId} 为准（凭据是密钥与地址的真相源）。
 *
 * <p>{@code capability} 白名单只含**真正经控制面解析**的能力（{@code ByokRoutingService.resolveProvider}
 * → {@code PlatformModelControlPlaneService.resolve}，与 {@code PlatformModelConfigSeeder} 播种集一致）。
 * 2026-08-30 起 {@code image_generation} 入控制面（PRD §4.10 三层模型配置：治理台平台模型 &gt;
 * 商家/用户 BYOK；静态 env 仅兜底）；任务书 #64 起 {@code video_generation}（视频生成）与
 * {@code video_tts}（视频配音）也入控制面——env 型 provider 配置随卡2「先配后删」退场，
 * 两者均**不进 BYOK 白名单**（平台链路，参照 {@code content_fix} 先例）。
 */
public record CreatePlatformModelRequest(
        @NotBlank(message = "capability 必填")
        @Pattern(regexp = "text|voice|retrieval|image_edit|content_safety|image_generation|content_fix"
                        + "|video_generation|video_tts|video_qa",
                message = "capability 必须是 text、voice、retrieval、image_edit、content_safety、"
                        + "image_generation、content_fix、video_generation、video_tts 或 video_qa")
        String capability,
        @NotBlank(message = "modelRole 必填")
        @Pattern(regexp = "primary|backup", message = "modelRole 必须是 primary 或 backup") String modelRole,
        java.util.UUID credentialId,
        @Pattern(regexp = PlatformProviderNames.PATTERN,
                message = PlatformProviderNames.MESSAGE) String provider,
        @NotBlank(message = "model 必填") String model,
        String baseUrl,
        @Min(value = 1, message = "maxConcurrency 必须大于 0")
        @Max(value = 1000, message = "maxConcurrency 不能超过 1000") Integer maxConcurrency,
        @Pattern(regexp = "healthy|degraded|unhealthy", message = "healthStatus 必须是 healthy/degraded/unhealthy")
        String healthStatus) {
}
