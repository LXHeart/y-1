package com.grassland.intelligence.articleimage;

/** multipart 参考图，保持 MIME 与原始字节。 */
public record ReferenceImage(String mimeType, byte[] bytes) {
    public ReferenceImage {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
