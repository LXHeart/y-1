package com.grassland.intelligence.articleimage;

/** OpenAI-compatible images/generations 的首张图片结果。 */
public record GeneratedImage(String imageUrl, String base64, String revisedPrompt) {}
