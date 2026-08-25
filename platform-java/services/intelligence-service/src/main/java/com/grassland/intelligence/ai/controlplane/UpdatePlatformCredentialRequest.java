package com.grassland.intelligence.ai.controlplane;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 修改平台凭据的连接信息（PUT /api/admin/ai/credentials/{id}，admin）。
 *
 * <p><b>刻意不含 apiKey</b>（D5）：换密钥走独立的 {@code PUT /{id}/key}。老 analysis settings 那套
 * 「留空=保留、空格=清空」的约定不复制到新表——它需要一整段注释才能解释清楚。
 */
public record UpdatePlatformCredentialRequest(
        @NotBlank(message = "name 必填")
        @Size(max = 128, message = "name 不能超过 128 字符") String name,
        @NotBlank(message = "provider 必填")
        @Pattern(regexp = "qwen|openai-compatible|sandbox",
                message = "平台 provider 必须是 qwen、openai-compatible 或 sandbox") String provider,
        @NotBlank(message = "baseUrl 必填")
        @Size(max = 1000, message = "baseUrl 不能超过 1000 字符") String baseUrl) {
}
