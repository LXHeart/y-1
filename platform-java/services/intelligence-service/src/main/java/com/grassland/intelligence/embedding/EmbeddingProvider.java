package com.grassland.intelligence.embedding;

import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Embedding Provider 端口（任务书 #33）。实现负责把规范化文本映射为固定维度向量；
 * 返回向量必须维度正确、元素有限且范数非零，非法输出一律视为 Provider 失败。
 */
public interface EmbeddingProvider {

    String provider();

    /** 向量语义版本；索引与查询必须使用同一版本才可比较。 */
    String algorithmVersion();

    int dimensions();

    Mono<Result> embed(String normalizedText);

    record Result(List<Double> vector, int inputTokens, boolean sandbox) {}
}
