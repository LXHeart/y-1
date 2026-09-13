package com.grassland.intelligence.creationstudio;

import com.grassland.intelligence.creationstudio.source.SourceDocumentRepository;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-02：草稿 workspace.inputs.studio 引用的归属校验（不能只校验 UUID 形状）。
 *
 * <p>C101-02 只开放 sourceDocumentId（必须为本人、且属于当前草稿）与 recipe（静态目录
 * id+version 校验）；visualPlan／activeVisualJobId／lastProposalId 在负责卡（05/10/04）实现前
 * 一律拒绝写入。studio 引用仅限文章创作（capability=article；缺省视为 article 默认，
 * 与读侧回填一致——视频／朋友圈／点评草稿不得注入 studio）。
 */
@Component
public class CreationStudioReferenceValidator {

    private final SourceDocumentRepository sources;

    public CreationStudioReferenceValidator(SourceDocumentRepository sources) {
        this.sources = sources;
    }

    public Mono<Void> validateWorkspace(Map<String, Object> workspace, Caller caller, UUID draftId) {
        Object inputsRaw = workspace == null ? null : workspace.get("inputs");
        if (!(inputsRaw instanceof Map<?, ?> inputs)) {
            return Mono.empty();
        }
        Object studioRaw = inputs.get("studio");
        if (studioRaw == null) {
            return Mono.empty();
        }
        if (!(studioRaw instanceof Map<?, ?> studio)) {
            throw invalid("workspace.inputs.studio 必须是对象");
        }
        Object capability = workspace.get("capability");
        if (capability != null && !"article".equals(capability)) {
            throw invalid("仅文章创作支持 studio 引用");
        }
        validateRecipe(studio.get("recipe"));
        rejectFutureRefs(studio);
        Object sourceId = studio.get("sourceDocumentId");
        if (!(sourceId instanceof String sourceText) || sourceText.isBlank()) {
            return Mono.empty();
        }
        UUID documentId;
        try {
            documentId = UUID.fromString(sourceText);
        } catch (IllegalArgumentException error) {
            throw invalid("workspace.inputs.studio.sourceDocumentId 必须是 UUID");
        }
        if (draftId == null) {
            throw invalid("来源引用必须绑定当前草稿");
        }
        return sources.findById(documentId)
                .filter(document -> caller.accountId().equals(document.ownerAccountId()))
                .filter(document -> draftId.equals(document.draftId()))
                .switchIfEmpty(Mono.error(
                        new IntelligenceException(404, "STUDIO_NOT_FOUND", "来源引用不属于当前草稿")))
                .then();
    }

    /** recipe 是静态目录引用（C101-01 契约），不查库；已知 id + 精确 version 才可写。 */
    private static void validateRecipe(Object recipe) {
        if (recipe == null) {
            return;
        }
        if (!(recipe instanceof Map<?, ?> recipeMap)) {
            throw invalid("workspace.inputs.studio.recipe 必须是对象");
        }
        Object id = recipeMap.get("id");
        Object version = recipeMap.get("version");
        if (!(id instanceof String idText) || CreationRecipeCatalog.byId(idText) == null) {
            throw invalid("workspace.inputs.studio.recipe.id 未知");
        }
        if (!(version instanceof String versionText)
                || !versionText.equals(CreationRecipeCatalog.byId(idText).version())) {
            throw invalid("workspace.inputs.studio.recipe.version 不匹配");
        }
    }

    private static void rejectFutureRefs(Map<?, ?> studio) {
        if (studio.get("visualPlan") != null) {
            throw invalid("视觉计划引用尚未开放写入");
        }
        if (studio.get("activeVisualJobId") != null) {
            throw invalid("视觉任务引用尚未开放写入");
        }
        if (studio.get("lastProposalId") != null) {
            throw invalid("文本建议引用尚未开放写入");
        }
    }

    private static IntelligenceException invalid(String message) {
        return new IntelligenceException(400, "STUDIO_INVALID_INPUT", message);
    }
}
