package com.grassland.intelligence.ai;

/**
 * 多模态消息内容片断（OpenAI 兼容 chat content parts）。文本片断或图片片断（data: URI 或 http(s) URL）。
 * 用于 {@link ChatMessage#user(java.util.List)} 多模态调用（如视频脚本生成把素材图片连同文本一起发给视觉模型）。
 */
public sealed interface ContentPart permits ContentPart.Text, ContentPart.Image {

    /** 文本片断。 */
    record Text(String text) implements ContentPart {}

    /** 图片片断；{@code url} 为 {@code data:image/...;base64,...} 或 http(s) URL。 */
    record Image(String url) implements ContentPart {}

    static Text text(String text) {
        return new Text(text);
    }

    static Image image(String url) {
        return new Image(url);
    }
}
