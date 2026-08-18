package com.grassland.intelligence.speech;

import java.util.LinkedHashMap;
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

    public SpeechTranscriptionController(SpeechTranscriptionService service) {
        this.service = service;
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
