package com.grassland.intelligence.creationstudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.creationassistant.CreationDraft;
import com.grassland.intelligence.creationassistant.CreationDraftService;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-04：studio 上下文读取与 baseContentHash（§6.5）。
 *
 * baseContentHash 是「当前正文 LF 版本、articleTitle、contentMode、questionText/questionRef、
 * platform/contentForm、影响生成的 Brief 字段」的规范化 JSON 摘要；不含导航 title、发布描述、
 * 下载 URL、UI 主题或保存时间。正文只统一 CRLF/CR 为 LF，不做 NFKC／全半角转换。
 */
@Service
public class CreationStudioContextService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CreationDraftService drafts;

    public CreationStudioContextService(CreationDraftService drafts) {
        this.drafts = drafts;
    }

    public record DraftContext(CreationDraft draft, String baseContentHash, String lfContent) {
    }

    public Mono<DraftContext> loadOwnedDraftContext(String draftId, Caller caller) {
        return drafts.loadOwned(draftId, caller.accountId()).map(draft -> {
            String lfContent = normalizeLf(draft.content());
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("content", lfContent);
            canonical.put("articleTitle", draft.articleTitle());
            canonical.put("contentMode", draft.contentMode() == null ? null : draft.contentMode().db());
            canonical.put("questionText", draft.questionText());
            canonical.put("questionRef", draft.questionRef());
            canonical.put("platform", draft.platform());
            canonical.put("contentForm", draft.contentForm());
            canonical.put("brief", draft.workspace() == null ? null : draft.workspace().get("brief"));
            return new DraftContext(draft, sha256Json(canonical), lfContent);
        });
    }

    /** 服务端按当前草稿重算 baseContentHash 与建议基线比对（§6.5：不能信任客户端版本引用）。 */
    public static String computeBaseContentHash(CreationDraft draft) {
        String lfContent = normalizeLf(draft.content());
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("content", lfContent);
        canonical.put("articleTitle", draft.articleTitle());
        canonical.put("contentMode", draft.contentMode() == null ? null : draft.contentMode().db());
        canonical.put("questionText", draft.questionText());
        canonical.put("questionRef", draft.questionRef());
        canonical.put("platform", draft.platform());
        canonical.put("contentForm", draft.contentForm());
        canonical.put("brief", draft.workspace() == null ? null : draft.workspace().get("brief"));
        return sha256Json(canonical);
    }

    static String normalizeLf(String text) {
        if (text == null || text.indexOf('\r') < 0) {
            return text == null ? "" : text;
        }
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    private static String sha256Json(Map<String, Object> canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(MAPPER.writeValueAsBytes(canonical)));
        } catch (Exception error) {
            throw new IllegalStateException("上下文摘要计算失败", error);
        }
    }

    static IntelligenceException invalid(String message) {
        return new IntelligenceException(400, "STUDIO_INVALID_INPUT", message);
    }
}
