package com.grassland.crypto;

/**
 * API Key 掩码提示工具（GL-P3-AI-001 Phase 1）。
 *
 * <p>生成类似 {@code sk-***xyz} 的掩码提示，保留前缀和后缀各 3-4 字符。
 * 用户可以看到这是自己的密钥，但无法获取完整明文。
 */
public final class MaskedKey {

    private MaskedKey() {}

    private static final int PREFIX_CHARS = 3;
    private static final int SUFFIX_CHARS = 4;

    /**
     * 生成掩码提示。
     *
     * @param plaintext 原始明文（如 API Key）
     * @return 掩码提示，如 {@code sk-***xyz}；如果 plaintext 过短则返回 {@code ***}
     * @throws IllegalArgumentException 如果 plaintext 为空
     */
    public static String mask(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("plaintext cannot be blank");
        }

        int len = plaintext.length();

        // 密钥太短，全部掩码
        if (len <= PREFIX_CHARS + SUFFIX_CHARS) {
            return "***";
        }

        String prefix = plaintext.substring(0, PREFIX_CHARS);
        String suffix = plaintext.substring(len - SUFFIX_CHARS);
        return prefix + "***" + suffix;
    }

    /**
     * 验证密钥是否只包含可打印 ASCII 字符（用于安全检查）。
     *
     * @param key 要验证的密钥
     * @return 如果只包含可打印 ASCII 字符（0x20-0x7E）返回 true
     */
    public static boolean isSafePrintable(String key) {
        if (key == null) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }
}
