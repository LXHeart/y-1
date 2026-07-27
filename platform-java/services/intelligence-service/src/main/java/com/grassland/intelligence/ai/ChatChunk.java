package com.grassland.intelligence.ai;

/** 流式文本生成的一个增量片段（对应上游 {@code choices[0].delta.content}）。空片段由 client 过滤。 */
public record ChatChunk(String content) {}
