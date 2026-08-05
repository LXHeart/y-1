package com.grassland.intelligence.media;

import java.util.Set;

/** KYB 证据的服务端 MIME 白名单与文件签名校验。 */
final class KybMediaPolicy {

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg", "image/png", "application/pdf");
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final byte[] PDF_SIGNATURE = {0x25, 0x50, 0x44, 0x46, 0x2d};

    private KybMediaPolicy() {
    }

    static boolean isAllowedMime(String mimeType) {
        return ALLOWED_MIME_TYPES.contains(mimeType);
    }

    static boolean hasExpectedSignature(String mimeType, byte[] bytes) {
        if (mimeType == null || bytes == null) {
            return false;
        }
        return switch (mimeType) {
            case "image/jpeg" -> startsWith(bytes, JPEG_SIGNATURE);
            case "image/png" -> startsWith(bytes, PNG_SIGNATURE);
            case "application/pdf" -> startsWith(bytes, PDF_SIGNATURE);
            default -> false;
        };
    }

    private static boolean startsWith(byte[] bytes, byte[] signature) {
        if (bytes.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (bytes[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
