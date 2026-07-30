package com.grassland.intelligence.videorecreation;

/**
 * JavaScript {@code String.prototype.trim()} 的最小兼容实现（草场 Slice 9）。
 *
 * <p>Zod 的 {@code z.string().trim()} 使用 ECMAScript whitespace；Java {@link String#trim()} 不会移除 NBSP
 * （U+00A0）等字符。为保持 legacy 请求契约，这里按 ECMAScript WhiteSpace/LineTerminator 集合与 BOM（U+FEFF）去除。
 */
final class LegacyStringValidation {

    private LegacyStringValidation() {}

    static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isEcmaWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = value.codePointBefore(end);
            if (!isEcmaWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static boolean isEcmaWhitespace(int codePoint) {
        return codePoint == 0x0009 // TAB
                || codePoint == 0x000a // LF
                || codePoint == 0x000b // VT
                || codePoint == 0x000c // FF
                || codePoint == 0x000d // CR
                || codePoint == 0x0020 // SPACE
                || codePoint == 0x00a0 // NO-BREAK SPACE
                || codePoint == 0x1680
                || (codePoint >= 0x2000 && codePoint <= 0x200a)
                || codePoint == 0x2028
                || codePoint == 0x2029
                || codePoint == 0x202f
                || codePoint == 0x205f
                || codePoint == 0x3000
                || codePoint == 0xfeff;
    }
}
