package com.grassland.intelligence.ai.controlplane;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 整份覆盖平台凭据的已勾选模型（PUT /api/admin/ai/credentials/{id}/selected-models，admin）。
 *
 * <p>空列表合法 = 取消全部勾选。上限 200：上游 /models 有时返回上千个模型 id，
 * 全勾进来对下拉毫无用处，且会让整份覆盖的逐条 INSERT 变慢。
 */
public record ReplaceSelectedModelsRequest(
        @NotNull(message = "models 必填")
        @Size(max = 200, message = "勾选模型不能超过 200 个")
        List<@Valid SelectedModelEntry> models) {

    List<PlatformCredentialModelRepository.SelectedModel> toDomain() {
        return models.stream()
                .map(entry -> new PlatformCredentialModelRepository.SelectedModel(
                        entry.id().trim(), entry.ownedBy()))
                .distinct()
                .toList();
    }

    /**
     * {@code id} 收紧到模型 id 的合法字符集：它会原样进 {@code platform_model_config.model}，
     * 再进出站请求体。放开任意字符等于把上游响应里的字符串直接当配置值用。
     */
    public record SelectedModelEntry(
            @NotBlank(message = "模型 id 必填")
            @Size(max = 128, message = "模型 id 不能超过 128 字符")
            @Pattern(regexp = "[A-Za-z0-9._:@/-]+", message = "模型 id 含非法字符")
            String id,
            @Size(max = 128, message = "ownedBy 不能超过 128 字符") String ownedBy) {
    }
}
