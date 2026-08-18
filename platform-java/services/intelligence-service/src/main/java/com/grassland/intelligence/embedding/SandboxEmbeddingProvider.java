package com.grassland.intelligence.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Sandbox Embedding（任务书 #33）：SHA-256 确定性投影。每个 token 哈希后取 8 个维度槽位与符号，
 * 按词频加权累加，最终 L2 归一化。无网络、无随机源；相同文本逐元素一致。
 */
@Component
public final class SandboxEmbeddingProvider implements EmbeddingProvider {

    static final int DIMENSIONS = 256;
    private static final int SLICES_PER_TOKEN = 8;
    private static final String ALGORITHM_VERSION = "sandbox-hash-v1";

    @Override
    public String provider() {
        return "sandbox";
    }

    @Override
    public String algorithmVersion() {
        return ALGORITHM_VERSION;
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public Mono<Result> embed(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return Mono.error(new IllegalArgumentException("Embedding 输入文本不能为空"));
        }
        String[] tokens = normalizedText.trim().split("\\s+");
        Map<String, Long> frequency = new HashMap<>();
        for (String token : tokens) {
            frequency.merge(token, 1L, Long::sum);
        }
        double[] vector = new double[DIMENSIONS];
        for (Map.Entry<String, Long> entry : frequency.entrySet()) {
            byte[] digest = sha256(entry.getKey());
            double weight = entry.getValue();
            for (int slice = 0; slice < SLICES_PER_TOKEN; slice++) {
                int index = (((digest[4 * slice] & 0xFF) << 8) | (digest[4 * slice + 1] & 0xFF)) % DIMENSIONS;
                double sign = (digest[4 * slice + 2] & 1) == 0 ? 1.0 : -1.0;
                vector[index] += sign * weight;
            }
        }
        double norm = Math.sqrt(Arrays.stream(vector).map(v -> v * v).sum());
        if (!Double.isFinite(norm) || norm == 0.0) {
            return Mono.error(new IllegalStateException("Sandbox Embedding 向量范数非法"));
        }
        List<Double> normalized = new ArrayList<>(DIMENSIONS);
        for (double value : vector) {
            normalized.add(value / norm);
        }
        return Mono.just(new Result(List.copyOf(normalized), tokens.length, true));
    }

    private static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
