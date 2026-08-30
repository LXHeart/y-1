package com.grassland.intelligence.ai.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/** 任务书 #58 决策 E：env bootstrap 兜底删除后的三态语义。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProviderKeyDecryptor（决策 E：无 env 兜底）")
class ProviderKeyDecryptorTest {

    @Mock
    ObjectProvider<EnvelopeEncryption> encryptionProvider;

    @Mock
    EnvelopeEncryption encryption;

    @Test
    @DisplayName("有密文（BYOK/平台凭据）→ 解密返回明文")
    void decryptsCiphertext() {
        ProviderResolution byok = ProviderResolution.byok(
                "qwen", "https://qwen.example/v1", "model", "ciphertext", "v1");
        org.mockito.Mockito.when(encryptionProvider.getIfAvailable()).thenReturn(encryption);
        org.mockito.Mockito.when(encryption.decrypt("ciphertext")).thenReturn("plain-key");

        assertThat(new ProviderKeyDecryptor(encryptionProvider).decryptIfNeeded(byok)).isEqualTo("plain-key");
    }

    @Test
    @DisplayName("内置 Sandbox 平台解析 → null（免密钥）")
    void sandboxPlatformNeedsNoKey() {
        ProviderResolution sandbox = ProviderResolution.platform(
                null, "sandbox", "sandbox-speech-v1", "https://sandbox.invalid", 1, null);

        assertThat(new ProviderKeyDecryptor(encryptionProvider).decryptIfNeeded(sandbox)).isNull();
    }

    @Test
    @DisplayName("真实平台解析无凭据密钥 → 503 平台凭据缺失（不回落 env）")
    void platformWithoutKeyFailsClosed() {
        ProviderResolution platform = ProviderResolution.platform(
                UUID.randomUUID(), "qwen", "https://qwen.example/v1", "qwen-plus", 1, null);

        assertThatThrownBy(() -> new ProviderKeyDecryptor(encryptionProvider).decryptIfNeeded(platform))
                .isInstanceOfSatisfying(IntelligenceException.class,
                        error -> assertThat(error.status()).isEqualTo(503))
                .hasMessageContaining("平台凭据缺失");
    }

    @Test
    @DisplayName("DENIED → null（无密可解）")
    void deniedResolutionYieldsNull() {
        assertThat(new ProviderKeyDecryptor(encryptionProvider)
                .decryptIfNeeded(ProviderResolution.denied("no_platform_model"))).isNull();
    }
}
