package com.grassland.intelligence.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.TextCompletionCommand;
import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@ConditionalOnProperty(prefix = "object-storage", name = "enabled", havingValue = "true")
public class KybDocumentAnalysisService {

    private static final String KYB_PURPOSE = MediaPurpose.MERCHANT_KYB.db();
    private static final Set<String> TYPES = Set.of(
            "business_license", "legal_person_id_front", "legal_person_id_back");
    private static final String FAILURE_MESSAGE = "KYB 证照识别服务暂不可用";

    private final AiCapabilityAdapter ai;
    private final MediaReferenceRepository mediaRefs;
    private final ObjectStorageAdapter storage;
    private final ObjectMapper mapper;
    private final String provider;
    private final String model;
    private final Duration timeout;

    public KybDocumentAnalysisService(
            AiCapabilityAdapter ai,
            MediaReferenceRepository mediaRefs,
            ObjectStorageAdapter storage,
            @Value("${ai.kyb-document.provider:qwen}") String provider,
            @Value("${ai.kyb-document.model:qwen-vl}") String model,
            @Value("${ai.kyb-document.timeout-ms:60000}") long timeoutMs) {
        this.ai = ai;
        this.mediaRefs = mediaRefs;
        this.storage = storage;
        this.mapper = new ObjectMapper();
        this.provider = provider;
        this.model = model;
        this.timeout = Duration.ofMillis(Math.max(1000, Math.min(timeoutMs, 600_000)));
    }

    public Mono<Result> analyze(UUID mediaId, String organizationId, String documentType) {
        String type = normalizeType(documentType);
        if (!"qwen".equalsIgnoreCase(provider)) {
            return Mono.error(new IntelligenceException(503, "KYB 证照识别 provider 未配置"));
        }
        return evidence(mediaId, organizationId)
                .flatMap(ref -> readBytes(ref)
                        .flatMap(bytes -> ai.completeText(new TextCompletionCommand(
                                List.of(ChatMessage.user(List.of(
                                        ContentPart.image(dataUri(ref.mimeType(), bytes)),
                                        ContentPart.text(prompt(type))))),
                                FAILURE_MESSAGE,
                                timeout))))
                .map(content -> normalize(content, type));
    }

    private Mono<MediaReference> evidence(UUID id, String organizationId) {
        return mediaRefs.findById(id)
                .filter(ref -> organizationId.equals(ref.organizationId()))
                .filter(ref -> KYB_PURPOSE.equals(ref.purpose()))
                .filter(ref -> KYB_PURPOSE.equals(ref.domainType()))
                .filter(ref -> organizationId.equals(ref.domainId()))
                .filter(ref -> ref.status() == MediaStatus.ACTIVE)
                .filter(ref -> ref.expiresAt() == null || ref.expiresAt().isAfter(Instant.now()))
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "KYB 材料不存在或不可用")));
    }

    private Mono<byte[]> readBytes(MediaReference ref) {
        return Mono.fromCallable(() -> storage.getObject(ref.objectKey()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Result normalize(String content, String expectedType) {
        try {
            JsonNode root = mapper.readTree(stripFence(content));
            if (root == null || !root.isObject() || !root.path("fields").isObject()) {
                throw invalid();
            }
            String documentType = text(root, "documentType");
            if (!expectedType.equals(documentType)) {
                throw invalid();
            }
            double confidence = root.path("confidence").asDouble(-1);
            if (confidence < 0 || confidence > 1) {
                throw invalid();
            }
            return new Result(1, documentType, confidence, root.path("fields"), provider, model);
        } catch (IntelligenceException error) {
            throw error;
        } catch (Exception error) {
            throw invalid();
        }
    }

    private static String prompt(String type) {
        return """
                你是中国企业 KYB 证照 OCR 服务。只识别画面中清晰可见内容，不推测，不做最终审批。
                返回单个 JSON 对象，不要 Markdown：
                {"documentType":"%s","confidence":0.0,"fields":{}}
                documentType 必须与请求一致。confidence 范围 0 到 1。
                business_license fields: companyName, unifiedSocialCreditCode, legalRepresentative,
                registeredAddress, validFrom, validUntil。
                legal_person_id_front fields: name, idNumber, side（固定 front）, validUntil（看不到则 null）。
                legal_person_id_back fields: issuingAuthority, validFrom, validUntil, side（固定 back）。
                看不清的字段用 null。不要输出性别、民族、住址等非必要身份证字段。
                """.formatted(type);
    }

    private static String normalizeType(String value) {
        String type = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new IntelligenceException(400, "不支持的 KYB 证照类型");
        }
        return type;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText().trim().toLowerCase(Locale.ROOT) : null;
    }

    private static String dataUri(String mimeType, byte[] bytes) {
        return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static String stripFence(String value) {
        String text = value == null ? "" : value.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int newline = text.indexOf('\n');
        int end = text.lastIndexOf("```");
        return newline >= 0 && end > newline ? text.substring(newline + 1, end).trim() : text;
    }

    private static IntelligenceException invalid() {
        return new IntelligenceException(502, "KYB 证照识别服务返回了无效数据");
    }

    public record Result(
            int schemaVersion,
            String documentType,
            double confidence,
            JsonNode fields,
            String provider,
            String model) {}
}
