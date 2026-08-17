package com.grassland.intelligence.contentsafety;

import com.grassland.intelligence.contentsafety.SafetyReport.Finding;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 内容安全编排（任务书 #34 / ADR-D16 D7/D8）：L1 确定性层永远跑；L2 深检仅长文本（≥200 字符）且
 * 控制面已配置 content_safety 模型时附加（失败/未配置降级为仅 L1，{@code deepCheck:false}）。
 *
 * <p>advisory 姿态（D6）：任何内部失败都不抛错——返回 L1 结果。深检 findings 折叠进同一列表
 * （来源标 deep=true），severity 排序留给前端。
 */
@Component
public class ContentSafetyService {

    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final ContentSafetyAiChecker aiChecker;

    public ContentSafetyService(ContentSafetyAiChecker aiChecker) {
        this.aiChecker = aiChecker;
    }

    /** 完整检查（生成流内联与手动复查共用）。永不 error。 */
    public Mono<SafetyReport> check(ServerWebExchange exchange, String text) {
        List<Finding> shallow = ContentSafetyChecker.check(text);
        return aiChecker.deepCheck(exchange, text)
                .map(deep -> new SafetyReport(
                        merge(shallow, deep), ContentSafetyLexicon.version(), true))
                .defaultIfEmpty(SafetyReport.shallow(shallow));
    }

    /** 仅 L1（不需要 exchange/模型的调用方——短文本路径等价于 check 的降级形态）。 */
    public SafetyReport checkShallow(String text) {
        return SafetyReport.shallow(ContentSafetyChecker.check(text));
    }

    /**
     * 生成流内联接入（D8）：流尾追加独立 {@code {"type":"safety","safety":{...}}} 帧。
     * 检查文本由 extractor 从各帧累积（chunk 流取 content 字段、result 帧取 copy/review 等）；
     * 检查失败降级为空 findings 帧，绝不替换/中断生成主帧——旧消费器忽略未知帧，兼容不破坏。
     */
    public Flux<String> appendSafetyFrame(
            ServerWebExchange exchange, Flux<String> frames, Function<String, String> textExtractor) {
        StringBuilder accumulated = new StringBuilder();
        return frames
                .doOnNext(frame -> {
                    String text = textExtractor.apply(frame);
                    if (text != null && !text.isBlank()) {
                        accumulated.append(text);
                    }
                })
                .concatWith(Mono.defer(() -> safetyFrame(exchange, accumulated.toString())
                        .onErrorResume(error -> Mono.just(safetyFrameJson(SafetyReport.emptyShallow())))));
    }

    /** 单帧 JSON（手动拼接安全字面量避免双重编码；findings 数组由序列化产出）。 */
    public Mono<String> safetyFrame(ServerWebExchange exchange, String text) {
        return check(exchange, text).map(this::safetyFrameJson);
    }

    /** 供非流式响应（titles 等）内嵌 data 用：Map 形态。 */
    public Map<String, Object> reportBody(SafetyReport report) {
        Map<String, Object> safety = new LinkedHashMap<>();
        safety.put("findings", report.findings().stream().map(ContentSafetyService::findingBody).toList());
        safety.put("lexiconVersion", report.lexiconVersion());
        safety.put("deepCheck", report.deepCheck());
        return safety;
    }

    private String safetyFrameJson(SafetyReport report) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "safety");
            envelope.put("safety", reportBody(report));
            return mapper.writeValueAsString(envelope);
        } catch (Exception error) {
            return "{\"type\":\"safety\",\"safety\":{\"findings\":[],\"lexiconVersion\":\"unknown\","
                    + "\"deepCheck\":false}}";
        }
    }

    private static Map<String, Object> findingBody(Finding f) {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("category", f.category());
        finding.put("severity", f.severity());
        finding.put("match", f.match());
        finding.put("index", f.index());
        finding.put("advice", f.advice());
        finding.put("deep", f.deep());
        return finding;
    }

    /** 帧文本提取器：chunk 流（content 字段累积）。 */
    public static Function<String, String> contentFieldExtractor() {
        return frame -> extractTextField(frame, "content");
    }

    /** 帧文本提取器：moments result 帧（copy 字段）。 */
    public static Function<String, String> momentsCopyExtractor() {
        return frame -> extractTextField(frame, "copy");
    }

    /** 帧文本提取器：图片评价 result 帧（data.review）。 */
    public static Function<String, String> reviewExtractor() {
        return frame -> extractTextField(frame, "review");
    }

    private static String extractTextField(String frameJson, String field) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(frameJson);
            if (node.has(field) && node.get(field).isTextual()) {
                return node.get(field).asText();
            }
            if (node.has("data") && node.get("data").has(field) && node.get("data").get(field).isTextual()) {
                return node.get("data").get(field).asText();
            }
            return null;
        } catch (Exception error) {
            return null;
        }
    }

    private static List<Finding> merge(List<Finding> shallow, List<Finding> deep) {
        if (deep == null || deep.isEmpty()) {
            return shallow;
        }
        // L2 同短语已命中 L1 时去重（词库命中带 index 更精确，保留 L1 版本）
        List<Finding> merged = new java.util.ArrayList<>(shallow);
        for (Finding finding : deep) {
            boolean duplicate = shallow.stream()
                    .anyMatch(existing -> existing.category().equals(finding.category())
                            && existing.match().contains(finding.match()));
            if (!duplicate) {
                merged.add(finding);
            }
        }
        return List.copyOf(merged);
    }
}
