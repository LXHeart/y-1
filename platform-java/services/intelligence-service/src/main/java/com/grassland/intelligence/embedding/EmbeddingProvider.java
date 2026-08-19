package com.grassland.intelligence.embedding;

import java.util.List;
import java.util.Set;
import com.grassland.intelligence.ai.ProviderInvocation;
import reactor.core.publisher.Mono;

/**
 * Embedding Provider 端口（任务书 #33）。实现负责把规范化文本映射为固定维度向量；
 * 返回向量必须维度正确、元素有限且范数非零，非法输出一律视为 Provider 失败。
 */
public interface EmbeddingProvider {

    String provider();

    default Set<String> aliases() {
        return Set.of();
    }

    /** 向量语义版本；索引与查询必须使用同一版本才可比较。 */
    String algorithmVersion();

    default String algorithmVersion(Command command) {
        return algorithmVersion();
    }

    int dimensions();

    Mono<Result> embed(String normalizedText);

    default Mono<Result> embed(Command command) {
        return embed(command == null ? null : command.normalizedText());
    }

    record Command(String normalizedText, ProviderInvocation invocation) {}

    record Result(List<Double> vector, int inputTokens, boolean sandbox) {}
}
