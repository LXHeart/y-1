package com.grassland.intelligence.creationassistant;

import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** A handoff copies one owned, saved revision; later source edits never rewrite its derivative. */
@Service
public class CreationDraftHandoffService {
    private final CreationDraftService service;
    private final CreationDraftRepository drafts;
    private final TransactionalOperator transactions;

    public CreationDraftHandoffService(CreationDraftService service, CreationDraftRepository drafts,
            TransactionalOperator transactions) {
        this.service = service;
        this.drafts = drafts;
        this.transactions = transactions;
    }

    public record Command(UUID requestId, int version, String target) {}

    public Mono<CreationDraftView> create(String sourceId, Caller caller, Command command) {
        if (command == null || command.requestId() == null || command.version() < 1
                || !List.of("video", "article").contains(command.target() == null ? "" : command.target())) {
            return Mono.error(new IntelligenceException(400, "HANDOFF_INVALID", "需要请求编号、保存版本和目标作品类型"));
        }
        return service.withStudioDraftLock(sourceId, caller, source ->
            drafts.findVersion(source.id(), command.version())
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "指定稿件版本不存在")))
                .flatMap(version -> copy(caller, source, version, command)))
            .as(transactions::transactional).map(CreationDraftView::of);
    }

    private Mono<CreationDraft> copy(Caller caller, CreationDraft source, CreationDraftVersion version, Command command) {
        String content = version.content();
        if (content == null || content.isBlank()) return Mono.error(new IntelligenceException(422, "源版本没有可交接的正文"));
        UUID targetId = UUID.nameUUIDFromBytes((caller.accountId() + ":work-handoff:" + command.requestId()).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> origin = Map.of("draftId", source.id().toString(), "version", version.version(),
                "title", version.title(), "contentHash", hash(content), "target", command.target());
        return drafts.findById(targetId).flatMap(existing -> {
            if (existing.deletedAt() != null || !existing.ownerAccountId().equals(caller.accountId())
                    || !origin.equals(existing.workspace().get("sourceWork"))) {
                return Mono.error(new IntelligenceException(409, "HANDOFF_CONFLICT", "该请求编号已用于其他交接或作品已删除"));
            }
            return Mono.just(existing);
        }).switchIfEmpty(Mono.defer(() -> {
            Map<String, Object> inputs = new LinkedHashMap<>();
            if (version.workspace().get("inputs") instanceof Map<?, ?> oldInputs) {
                for (String key : List.of("contextSnapshotId", "sourceContext")) {
                    if (oldInputs.get(key) != null) inputs.put(key, oldInputs.get(key));
                }
            }
            if ("video".equals(command.target())) {
                inputs.put("video", Map.of("form", Map.of("inputMode", "script", "script", content), "shots", List.of()));
            }
            Map<String, Object> workspace = new LinkedHashMap<>();
            workspace.put("schemaVersion", 1);
            workspace.put("capability", command.target());
            workspace.put("workflow", "video".equals(command.target()) ? "video-script" : "longform");
            workspace.put("currentStep", "video".equals(command.target()) ? "upload" : "content");
            workspace.put("inputs", inputs);
            workspace.put("sourceWork", origin);
            Map<String, Object> checked = CreationWorkspace.parse(workspace, command.target()).value();
            String title = version.title() + ("video".equals(command.target()) ? " · 视频" : " · 文稿");
            if (title.length() > 120) title = title.substring(0, 120);
            CreationDraft target = new CreationDraft(targetId, caller.accountId(), source.organizationId(), title,
                    version.sourceType(), version.taskId(), version.taskVersion(), version.storeId(),
                    version.platform(), "video".equals(command.target()) ? "video" : "graphic", version.topic(),
                    version.articleTitle(), version.outline(), content, DraftContentMode.ARTICLE, null, null,
                    DraftStatus.DRAFT, 1, null, null, null, checked, List.of(), List.of());
            return drafts.create(target).flatMap(saved -> Objects.equals(origin, saved.workspace().get("sourceWork"))
                    ? Mono.just(saved) : Mono.error(new IntelligenceException(409, "交接请求冲突")));
        }));
    }

    static String hash(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
