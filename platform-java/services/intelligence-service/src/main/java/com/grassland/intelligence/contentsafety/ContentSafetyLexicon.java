package com.grassland.intelligence.contentsafety;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.contentsafety.ContentSafetyLexiconRepository.Version;
import com.grassland.intelligence.security.IntelligenceException;
import io.r2dbc.spi.R2dbcException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** DB-versioned content-safety lexicon with a 60-second local active-version cache. */
@Component
public class ContentSafetyLexicon {

    private static final Logger log = LoggerFactory.getLogger(ContentSafetyLexicon.class);
    public static final int MAX_PAYLOAD_BYTES = 200 * 1024;
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final Pattern LABEL = Pattern.compile("lexicon-v[1-9][0-9]*");
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final SeedPayload SEED = loadSeed();
    private static final Lexicon FALLBACK = parse(SEED.payload(), SEED.label());

    private final ContentSafetyLexiconRepository repository;
    private final TransactionalOperator transactions;
    private volatile Lexicon cached = FALLBACK;
    private volatile Instant cacheExpiresAt = Instant.EPOCH;

    public ContentSafetyLexicon(
            ContentSafetyLexiconRepository repository, TransactionalOperator transactions) {
        this.repository = repository;
        this.transactions = transactions;
    }

    /** Seeds v1 only when the table is empty. */
    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        try {
            repository.count()
                    .flatMap(count -> count == 0
                            ? repository.insertSeed(SEED.label(), SEED.payload())
                            : repository.findActive())
                    .doOnNext(version -> {
                        cached = parse(version.payload(), version.label());
                        cacheExpiresAt = Instant.now().plus(CACHE_TTL);
                    })
                    .block(Duration.ofSeconds(20));
        } catch (RuntimeException error) {
            log.warn("content-safety lexicon seed unavailable; using bundled fallback", error);
            cached = FALLBACK;
            cacheExpiresAt = Instant.EPOCH;
        }
    }

    public Mono<Lexicon> activeLexicon() {
        Lexicon current = cached;
        if (Instant.now().isBefore(cacheExpiresAt)) return Mono.just(current);
        return repository.findActive()
                .map(version -> parse(version.payload(), version.label()))
                .defaultIfEmpty(FALLBACK)
                .doOnNext(value -> {
                    cached = value;
                    cacheExpiresAt = Instant.now().plus(CACHE_TTL);
                });
    }

    public Lexicon cachedLexicon() { return cached; }
    public void invalidate() { cacheExpiresAt = Instant.EPOCH; }

    public Mono<Version> createDraft(String label, JsonNode payload, String createdBy) {
        String normalizedLabel = label == null ? "" : label.trim();
        validateLabel(normalizedLabel);
        String json = validateAndSerialize(payload, normalizedLabel);
        return repository.createDraft(normalizedLabel, json, createdBy)
                .onErrorMap(ContentSafetyLexicon::isUniqueViolation,
                        error -> new IntelligenceException(409, "词库版本标签已存在"));
    }

    public Mono<Version> activate(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "词库版本不存在")))
                .flatMap(version -> {
                    if (!"draft".equals(version.status())) {
                        return Mono.error(new IntelligenceException(409, "只有草稿版本可以激活"));
                    }
                    parse(version.payload(), version.label());
                    return transactions.transactional(repository.retireCurrentActive()
                                    .then(repository.activateDraft(id)))
                            .switchIfEmpty(Mono.error(new IntelligenceException(409, "词库状态已变化")));
                })
                .doOnNext(version -> {
                    cached = parse(version.payload(), version.label());
                    cacheExpiresAt = Instant.now().plus(CACHE_TTL);
                });
    }

    public Mono<Version> retire(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "词库版本不存在")))
                .flatMap(version -> {
                    if ("active".equals(version.status())) {
                        return Mono.error(new IntelligenceException(409, "当前激活词库不可退役"));
                    }
                    if ("retired".equals(version.status())) return Mono.just(version);
                    return repository.retireDraft(id)
                            .switchIfEmpty(Mono.error(new IntelligenceException(409, "词库状态已变化")));
                });
    }

    public ContentSafetyLexiconRepository repository() { return repository; }

    /** Static compatibility for deterministic unit tests and classpath-only tools. */
    public static String version() { return FALLBACK.version(); }
    public static Lexicon get() { return FALLBACK; }

    public static Lexicon parse(JsonNode payload, String label) {
        try {
            return parse(MAPPER.writeValueAsString(payload), label);
        } catch (IntelligenceException error) {
            throw error;
        } catch (Exception error) {
            throw new IntelligenceException(400, "词库 payload 无法序列化");
        }
    }

    public static Lexicon parse(String payload, String label) {
        try {
            if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
                throw new IntelligenceException(400, "词库 payload 超过 200KB");
            }
            validateLabel(label);
            JsonNode root = MAPPER.readTree(payload);
            if (!root.isObject() || !root.path("categories").isArray()
                    || root.path("categories").isEmpty()) {
                throw new IntelligenceException(400, "词库 categories 结构无效");
            }
            if (!label.equals(root.path("version").asText(""))) {
                throw new IntelligenceException(400, "词库 payload.version 必须与 label 一致");
            }
            List<Category> categories = new ArrayList<>();
            for (JsonNode categoryNode : root.path("categories")) {
                String id = required(categoryNode, "id", 64);
                String severity = required(categoryNode, "severity", 16);
                String advice = required(categoryNode, "advice", 500);
                categories.add(new Category(id, severity, advice,
                        List.copyOf(toTextList(categoryNode.path("phrases"), 500)),
                        compilePatterns(categoryNode.path("patterns"))));
            }
            return new Lexicon(label, List.copyOf(categories),
                    List.copyOf(toTextList(root.path("exceptions"), 500)),
                    compilePatterns(root.path("exceptionPatterns")),
                    parseOverlays(root.path("overlays")));
        } catch (IntelligenceException error) {
            throw error;
        } catch (Exception error) {
            throw new IntelligenceException(400, "内容安全词库结构无效");
        }
    }

    private static String validateAndSerialize(JsonNode payload, String label) {
        if (payload == null) throw new IntelligenceException(400, "词库 payload 不能为空");
        parse(payload, label);
        try {
            String result = MAPPER.writeValueAsString(payload);
            if (result.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
                throw new IntelligenceException(400, "词库 payload 超过 200KB");
            }
            return result;
        } catch (IntelligenceException error) {
            throw error;
        } catch (Exception error) {
            throw new IntelligenceException(400, "词库 payload 无法序列化");
        }
    }

    private static List<CompiledPattern> compilePatterns(JsonNode node) {
        if (!node.isMissingNode() && !node.isArray()) {
            throw new IntelligenceException(400, "词库 patterns 结构无效");
        }
        List<CompiledPattern> result = new ArrayList<>();
        for (JsonNode patternNode : node) {
            String id = required(patternNode, "id", 64);
            String regex = required(patternNode, "regex", 200);
            try {
                result.add(new CompiledPattern(id, Pattern.compile(regex)));
            } catch (Exception error) {
                throw new IntelligenceException(400, "词库正则无法编译：" + id);
            }
        }
        return List.copyOf(result);
    }

    private static Overlays parseOverlays(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return Overlays.empty();
        if (!node.isObject()) throw new IntelligenceException(400, "词库 overlays 结构无效");
        Map<String, List<String>> platforms = stringListMap(node.path("platforms"));
        Map<String, List<String>> industries = stringListMap(node.path("industries"));
        Map<String, String> aliases = new LinkedHashMap<>();
        JsonNode aliasNode = node.path("industryAliases");
        if (!aliasNode.isMissingNode() && !aliasNode.isObject()) {
            throw new IntelligenceException(400, "词库 industryAliases 结构无效");
        }
        aliasNode.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual() || entry.getKey().isBlank()
                    || entry.getValue().asText().isBlank()) {
                throw new IntelligenceException(400, "词库 industryAliases 结构无效");
            }
            aliases.put(entry.getKey().trim(), entry.getValue().asText().trim());
        });
        return new Overlays(Map.copyOf(platforms), Map.copyOf(industries), Map.copyOf(aliases));
    }

    private static Map<String, List<String>> stringListMap(JsonNode node) {
        if (node.isMissingNode()) return Map.of();
        if (!node.isObject()) throw new IntelligenceException(400, "词库 overlay 映射无效");
        Map<String, List<String>> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> result.put(
                entry.getKey(), List.copyOf(toTextList(entry.getValue(), 200))));
        return result;
    }

    private static List<String> toTextList(JsonNode node, int maxLength) {
        if (!node.isMissingNode() && !node.isArray()) {
            throw new IntelligenceException(400, "词库短语数组无效");
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            if (!item.isTextual()) throw new IntelligenceException(400, "词库短语必须为文本");
            String text = item.asText().trim();
            if (text.isEmpty() || text.length() > maxLength) {
                throw new IntelligenceException(400, "词库短语长度无效");
            }
            values.add(text);
        });
        return values;
    }

    private static String required(JsonNode node, String field, int maxLength) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty() || value.length() > maxLength) {
            throw new IntelligenceException(400, "词库字段无效：" + field);
        }
        return value;
    }

    private static void validateLabel(String label) {
        if (label == null || !LABEL.matcher(label).matches()) {
            throw new IntelligenceException(400, "词库 label 必须使用 lexicon-vN 格式");
        }
    }

    private static boolean isUniqueViolation(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof R2dbcException r2dbc && "23505".equals(r2dbc.getSqlState())) {
                return true;
            }
        }
        return false;
    }

    private static SeedPayload loadSeed() {
        try (var stream = ContentSafetyLexicon.class.getClassLoader()
                .getResourceAsStream("contracts/content-safety-lexicon.json")) {
            if (stream == null) throw new IllegalStateException("content-safety-lexicon.json missing");
            String payload = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new SeedPayload(MAPPER.readTree(payload).path("version").asText(), payload);
        } catch (Exception error) {
            throw new IllegalStateException("内容安全词库种子加载失败", error);
        }
    }

    public record Lexicon(
            String version, List<Category> categories, List<String> exceptions,
            List<CompiledPattern> exceptionPatterns, Overlays overlays) {
        public boolean isExcepted(String text, int start, int end) {
            for (String exception : exceptions) {
                int exceptionStart = text.lastIndexOf(exception, start);
                if (exceptionStart >= 0 && exceptionStart <= start
                        && exceptionStart + exception.length() >= end) return true;
            }
            for (CompiledPattern pattern : exceptionPatterns) {
                var matcher = pattern.pattern().matcher(text);
                while (matcher.find()) {
                    if (matcher.start() <= start && matcher.end() >= end) return true;
                    if (matcher.start() > start) break;
                }
            }
            return false;
        }
    }

    public record Category(
            String id, String severity, String advice,
            List<String> phrases, List<CompiledPattern> patterns) {}
    public record CompiledPattern(String id, Pattern pattern) {}
    public record Overlays(
            Map<String, List<String>> platforms,
            Map<String, List<String>> industries,
            Map<String, String> industryAliases) {
        static Overlays empty() { return new Overlays(Map.of(), Map.of(), Map.of()); }
    }
    private record SeedPayload(String label, String payload) {}
}
