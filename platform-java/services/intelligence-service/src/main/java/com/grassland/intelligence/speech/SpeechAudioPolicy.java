package com.grassland.intelligence.speech;

import java.util.Locale;

/** Validates that a speech upload's leading bytes match its declared audio MIME type. */
public final class SpeechAudioPolicy {

    private SpeechAudioPolicy() {}

    public static boolean hasExpectedSignature(String mimeType, byte[] bytes) {
        if (mimeType == null || bytes == null) {
            return false;
        }
        return switch (mimeType.trim().toLowerCase(Locale.ROOT)) {
            case "audio/wav", "audio/x-wav" -> isRiffWave(bytes);
            case "audio/ogg" -> startsWith(bytes, 'O', 'g', 'g', 'S');
            case "audio/webm" -> startsWith(bytes, 0x1a, 0x45, 0xdf, 0xa3);
            case "audio/mp4" -> hasFtypBox(bytes);
            case "audio/mpeg" -> startsWith(bytes, 'I', 'D', '3') || hasMpegFrameHeader(bytes);
            default -> false;
        };
    }

    private static boolean isRiffWave(byte[] bytes) {
        return startsWith(bytes, 'R', 'I', 'F', 'F')
                && bytes.length >= 12
                && unsigned(bytes[8]) == 'W'
                && unsigned(bytes[9]) == 'A'
                && unsigned(bytes[10]) == 'V'
                && unsigned(bytes[11]) == 'E';
    }

    private static boolean hasFtypBox(byte[] bytes) {
        return bytes.length >= 8
                && unsigned(bytes[4]) == 'f'
                && unsigned(bytes[5]) == 't'
                && unsigned(bytes[6]) == 'y'
                && unsigned(bytes[7]) == 'p';
    }

    private static boolean hasMpegFrameHeader(byte[] bytes) {
        if (bytes.length < 4) {
            return false;
        }
        int second = unsigned(bytes[1]);
        int third = unsigned(bytes[2]);
        int version = (second >>> 3) & 0x03;
        int layer = (second >>> 1) & 0x03;
        int bitrate = (third >>> 4) & 0x0f;
        int sampleRate = (third >>> 2) & 0x03;
        return unsigned(bytes[0]) == 0xff
                && (second & 0xe0) == 0xe0
                && version != 0x01
                && layer != 0
                && bitrate != 0
                && bitrate != 0x0f
                && sampleRate != 0x03;
    }

    private static boolean startsWith(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (unsigned(bytes[index]) != signature[index]) {
                return false;
            }
        }
        return true;
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }
}
