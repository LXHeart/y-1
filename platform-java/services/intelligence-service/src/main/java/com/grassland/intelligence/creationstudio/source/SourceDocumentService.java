package com.grassland.intelligence.creationstudio.source;

import com.grassland.intelligence.creationassistant.CreationDraftService;
import com.grassland.intelligence.creationstudio.CreationStudioProperties;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-02（API101-03/04）：不可变来源文档创建与读取。
 *
 * <ul>
 * <li>先校验草稿 owner 与 expectedDraftVersion，再解析、原子 insert／重放；不隐式改写草稿
 * （sourceType/taskId/taskVersion/storeId/contextSnapshotId 不变）。</li>
 * <li>同 (owner, requestId) 同输入返回原记录（重放 200）；同键异参 409 STUDIO_OPERATION_CONFLICT。</li>
 * <li>draft-content 由服务端读取指定版本当前正文，不能由浏览器提交另一份文本冒充。</li>
 * <li>写开关关闭时拒绝新来源（404 STUDIO_DISABLED）；读取既有记录不受影响。</li>
 * </ul>
 */
@Service
public class SourceDocumentService {

    /** API101-03 完整 DTO（wire 解析在 Controller，经 StudioRequestValidator）。 */
    public record CreateCommand(UUID requestId, UUID draftId, int expectedDraftVersion, String kind, String title,
            String text, List<Map<String, Object>> sourceRefs) {

        /** 规范化 JSON SHA-256：键排序、缺省归一（title 缺省空、sourceRefs 缺省 []）。 */
        public String requestHash() {
            Map<String, Object> canonical = new TreeMap<>();
            canonical.put("draftId", draftId.toString());
            canonical.put("expectedDraftVersion", expectedDraftVersion);
            canonical.put("kind", kind);
            canonical.put("title", title == null ? "" : title);
            canonical.put("text", text);
            canonical.put("sourceRefs", sourceRefs == null ? List.of() : sourceRefs);
            return SourceDocumentParser.sha256(writeCanonical(canonical));
        }

        private static String writeCanonical(Map<String, Object> canonical) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(canonical);
            } catch (Exception error) {
                throw new IllegalStateException("来源请求规范化序列化失败", error);
            }
        }
    }

    public record CreateResult(SourceDocument document, boolean created) {
    }

    private final SourceDocumentRepository sources;
    private final CreationDraftService drafts;
    private final CreationStudioProperties properties;

    public SourceDocumentService(SourceDocumentRepository sources, CreationDraftService drafts,
            CreationStudioProperties properties) {
        this.sources = sources;
        this.drafts = drafts;
        this.properties = properties;
    }

    public Mono<CreateResult> create(Caller caller, CreateCommand command) {
        if (!properties.isWritesEnabled()) {
            return Mono.error(new IntelligenceException(404, "STUDIO_DISABLED", "创作工作台写入暂未开放"));
        }
        return drafts.loadOwned(command.draftId().toString(), caller.accountId()).flatMap(draft -> {
            if (draft.version() != command.expectedDraftVersion()) {
                return Mono.error(new IntelligenceException(409, "STUDIO_VERSION_CONFLICT", "草稿版本已变化，请刷新后重试"));
            }
            String rawText = command.text();
            if ("draft-content".equals(command.kind())) {
                if (rawText != null) {
                    return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT",
                            "kind=draft-content 不接受 text 字段"));
                }
                rawText = draft.content();
                if (rawText == null || rawText.isBlank()) {
                    return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "当前草稿没有可冻结的正文"));
                }
            } else if (rawText == null) {
                return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "缺少必填字段：text"));
            }
            UUID id = UUID.randomUUID();
            SourceDocumentParser.ParsedSource parsed = SourceDocumentParser.parse(id, command.kind(), rawText);
            SourceDocument document = new SourceDocument(id, caller.accountId(), draft.id(),
                    command.requestId().toString(), command.requestHash(), command.kind(), 1,
                    command.title() == null ? "" : command.title(), parsed.rawText(), parsed.normalizedMarkdown(),
                    parsed.contentHash(), parsed.blocks(),
                    command.sourceRefs() == null ? List.of() : List.copyOf(command.sourceRefs()), parsed.warnings(),
                    null);
            return sources.insert(document).flatMap(inserted -> inserted ? Mono.just(new CreateResult(document, true))
                    : sources.findByOwnerAndRequestId(caller.accountId(), command.requestId().toString())
                            .flatMap(existing -> existing.requestHash().equals(document.requestHash())
                                    ? Mono.just(new CreateResult(existing, false))
                                    : Mono.error(new IntelligenceException(409, "STUDIO_OPERATION_CONFLICT",
                                            "同一 requestId 已用于不同内容的来源导入"))));
        });
    }

    /** GET：仅 owner 且草稿未删可读；跨账号／软删草稿统一 404。 */
    public Mono<SourceDocument> loadOwned(String id, Caller caller) {
        UUID documentId;
        try {
            documentId = UUID.fromString(id);
        } catch (IllegalArgumentException error) {
            return Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "来源不存在"));
        }
        return sources.findById(documentId)
                .filter(document -> caller.accountId().equals(document.ownerAccountId()))
                .flatMap(document -> drafts
                        .loadOwned(document.draftId().toString(), caller.accountId()).thenReturn(document))
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "来源不存在")));
    }
}
