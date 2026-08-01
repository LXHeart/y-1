package com.grassland.intelligence.ai;

/**
 * 非流式多模态完成的解析结果（草场 Slice 13 Stage 5）。{@code content} = OpenAI 兼容
 * {@code choices[0].message.content}（可能为 JSON 文本，由调用方按业务归一）；{@code runId} = 上游
 * chat completion 顶层 {@code id}（前端「运行 ID」展示，可空——上游未返回时为 null）。
 *
 * @param content 模型返回的文本内容（非空）
 * @param runId 上游 run id（可空）
 */
public record MultimodalResult(String content, String runId) {
}
