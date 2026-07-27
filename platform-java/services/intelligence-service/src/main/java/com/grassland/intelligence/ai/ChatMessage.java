package com.grassland.intelligence.ai;

import java.util.List;

/**
 * OpenAI 兼容 chat 消息（role: system/user/assistant）。content 可为明文（{@link #content()}）
 * 或多模态片断列表（{@link #parts()}，见 {@link ContentPart}）。二选一：{@link #parts()} 非 null 即多模态。
 *
 * <p>纯文本调用用 {@link #system(String)}/{@link #user(String)}（content 明文，行为不变）；
 * 视觉等需带图场景用 {@link #user(List)}（content 为 text/image 片断数组）。
 */
public record ChatMessage(String role, String content, List<ContentPart> parts) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null);
    }

    /** 多模态用户消息（文本 + 图片片断）。 */
    public static ChatMessage user(List<ContentPart> parts) {
        return new ChatMessage("user", null, List.copyOf(parts));
    }

    public boolean multimodal() {
        return parts != null;
    }
}
