package com.grassland.intelligence.ai;

/**
 * 多模态消息内容片断（OpenAI 兼容 chat content parts）。文本 / 图片 / 视频片断（图片为 data: URI 或 http(s) URL，
 * 视频为 http(s) URL）。用于 {@link ChatMessage#user(java.util.List)} 多模态调用（如视频脚本生成把素材图片连同文本
 * 一起发给视觉模型，或 Bilibili 视频内容提取把公开视频地址连同提示一起发给视频理解模型）。
 */
public sealed interface ContentPart permits ContentPart.Text, ContentPart.Image, ContentPart.Video {

    /** 文本片断。 */
    record Text(String text) implements ContentPart {}

    /** 图片片断；{@code url} 为 {@code data:image/...;base64,...} 或 http(s) URL。 */
    record Image(String url) implements ContentPart {}

    /** 视频片断；{@code url} 为 http(s) 公网可达地址（LLM provider 直接拉取，序列化为 {@code video_url} part）。 */
    record Video(String url) implements ContentPart {}

    static Text text(String text) {
        return new Text(text);
    }

    static Image image(String url) {
        return new Image(url);
    }

    static Video video(String url) {
        return new Video(url);
    }
}
