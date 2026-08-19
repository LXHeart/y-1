package com.grassland.intelligence.speech;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/speech/transcriptions")
public final class SpeechTranscriptionController {

    private final SpeechTranscriptionService service;
    private final SpeechTranscriptionRepository repository;
    private final com.grassland.intelligence.security.IntelligenceCallerResolver callers;

    public SpeechTranscriptionController(
            SpeechTranscriptionService service,
            SpeechTranscriptionRepository repository,
            com.grassland.intelligence.security.IntelligenceCallerResolver callers) {
        this.service = service;
        this.repository = repository;
        this.callers = callers;
    }

    @PostMapping
    public Mono<ResponseEntity<Map<String, Object>>> create(
            @RequestBody CreateTranscriptionRequest body,
            ServerWebExchange exchange) {
        UUID mediaId = body == null ? null : body.mediaId();
        String language = body == null ? null : body.language();
        return service.create(exchange.getRequest(), mediaId, language)
                .map(transcription -> ResponseEntity.status(201).body(envelope(transcription)));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> get(
            @PathVariable UUID id,
            ServerWebExchange exchange) {
        return service.get(exchange.getRequest(), id)
                .map(transcription -> ResponseEntity.ok(envelope(transcription)));
    }

    /** owner 最近 20 条转写，按 created_at DESC（任务书 #43）。 */
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> list(ServerWebExchange exchange) {
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> repository.findRecentOwned(caller.accountId(), 20)
                        .map(SpeechTranscriptionController::listItem)
                        .collectList()
                        .map(items -> ResponseEntity.ok(Map.of("success", true, "data", Map.of("items", items)))));
    }

    private static Map<String, Object> listItem(SpeechTranscription t) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", t.id().toString());
        item.put("mediaReferenceId", t.mediaReferenceId().toString());
        item.put("durationMs", t.durationMs());
        item.put("status", t.status());
        item.put("detectedLanguage", t.detectedLanguage());
        item.put("createdAt", t.createdAt());
        // 仅 completed 且非空时带文本
        if ("completed".equals(t.status()) && t.transcriptText() != null && !t.transcriptText().isBlank()) {
            item.put("transcriptText", t.transcriptText());
        }
        return item;
    }

    private static Map<String, Object> envelope(SpeechTranscription transcription) {
        return Map.of("success", true, "data", response(transcription));
    }

    private static Map<String, Object> response(SpeechTranscription transcription) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", transcription.id().toString());
        data.put("mediaId", transcription.mediaReferenceId().toString());
        data.put("language", transcription.requestedLanguage());
        data.put("detectedLanguage", transcription.detectedLanguage());
        data.put("durationMs", transcription.durationMs());
        data.put("status", transcription.status());
        data.put("text", transcription.transcriptText());
        data.put("provider", transcription.provider());
        data.put("model", transcription.model());
        data.put("modelVersion", transcription.platformModelVersion());
        data.put("sandbox", "sandbox".equalsIgnoreCase(transcription.provider()));
        data.put("aiRunId", string(transcription.aiRunId()));
        data.put("failureCode", transcription.failureCode());
        data.put("createdAt", transcription.createdAt());
        data.put("updatedAt", transcription.updatedAt());
        data.put("completedAt", transcription.completedAt());
        return data;
    }

    private static String string(UUID value) {
        return value == null ? null : value.toString();
    }

    public record CreateTranscriptionRequest(UUID mediaId, String language) {}
}
