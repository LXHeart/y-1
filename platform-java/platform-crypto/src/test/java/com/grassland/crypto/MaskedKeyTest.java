package com.grassland.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MaskedKey")
class MaskedKeyTest {

    @Test
    @DisplayName("mask 保留前缀和后缀")
    void mask_retainsPrefixAndSuffix() {
        assertThat(MaskedKey.mask("sk-1234567890abcdef")).isEqualTo("sk-***cdef");
        assertThat(MaskedKey.mask("sk-test-key-value")).isEqualTo("sk-***alue");  // 后缀 4 字符: "alue"
        assertThat(MaskedKey.mask("pk_live_abc123xyz789")).isEqualTo("pk_***z789");  // 后缀 4 字符: "z789"
    }

    @Test
    @DisplayName("mask 处理短密钥")
    void mask_handlesShortKeys() {
        assertThat(MaskedKey.mask("ab")).isEqualTo("***");
        assertThat(MaskedKey.mask("abcdef")).isEqualTo("***");  // 6 < 3+4 = 7
        assertThat(MaskedKey.mask("abcdefgh")).isEqualTo("abc***efgh");  // 8 > 7，后缀 4 字符
    }

    @Test
    @DisplayName("mask 空或空白抛异常")
    void mask_blank_throws() {
        assertThatThrownBy(() -> MaskedKey.mask(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MaskedKey.mask(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MaskedKey.mask("  "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("isSafePrintable 验证可打印 ASCII")
    void isSafePrintable_validatesPrintableAscii() {
        assertThat(MaskedKey.isSafePrintable("sk-test123")).isTrue();
        assertThat(MaskedKey.isSafePrintable("abc!@#$%^&*()_+-=")).isTrue();
        assertThat(MaskedKey.isSafePrintable("sk-中文")).isFalse();  // 中文（非 ASCII）
        assertThat(MaskedKey.isSafePrintable("\n\t")).isFalse();  // 控制字符
        assertThat(MaskedKey.isSafePrintable(null)).isFalse();
    }
}
