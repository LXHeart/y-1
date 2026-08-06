package com.grassland.intelligence.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.InetAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ProviderUrlGuard} SSRF 闸门：结构校验 + 私有 IP 字面量拒绝（域名视为可信平台配置）。
 * 全部用 IP 字面量/纯结构输入，无 DNS——离线稳定。
 */
class ProviderUrlGuardTest {

    @Test
    @DisplayName("合法 https 域名与公共 IP 字面量通过")
    void acceptsPublicHostAndPublicIp() {
        assertThat(ProviderUrlGuard.validate("https://dashscope.aliyuncs.com"))
                .isEqualTo(URI.create("https://dashscope.aliyuncs.com"));
        assertThat(ProviderUrlGuard.validate("https://8.8.8.8")).isNotNull();
        // 域名视为可信（平台 env 配置）：localhost 是主机名而非 IP 字面量 → 结构校验通过。
        assertThat(ProviderUrlGuard.validate("http://localhost:8080")).isNotNull();
    }

    @Test
    @DisplayName("空 / 非法格式 / 非 http(s) scheme 拒绝")
    void rejectsMalformedAndNonHttp() {
        assertThatThrownBy(() -> ProviderUrlGuard.validate(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProviderUrlGuard.validate("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProviderUrlGuard.validate("ftp://example.com"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("http/https");
        assertThatThrownBy(() -> ProviderUrlGuard.validate("example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("userinfo 拒绝（base-url 不得带凭据）")
    void rejectsUserInfo() {
        assertThatThrownBy(() -> ProviderUrlGuard.validate("https://user:pass@example.com"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("凭据");
    }

    @Test
    @DisplayName("私有 IPv4 字面量拒绝：环回 / 站点本地 / 链路本地")
    void rejectsPrivateIpv4Literals() {
        assertThatThrownBy(() -> ProviderUrlGuard.validate("http://127.0.0.1")).hasMessageContaining("已拒绝");
        assertThatThrownBy(() -> ProviderUrlGuard.validate("http://10.0.0.5")).hasMessageContaining("已拒绝");
        assertThatThrownBy(() -> ProviderUrlGuard.validate("http://192.168.1.1")).hasMessageContaining("已拒绝");
        assertThatThrownBy(() -> ProviderUrlGuard.validate("http://172.16.0.1")).hasMessageContaining("已拒绝");
        assertThatThrownBy(() -> ProviderUrlGuard.validate("http://169.254.169.254")).hasMessageContaining("已拒绝");
    }

    @Test
    @DisplayName("IPv6 环回字面量拒绝")
    void rejectsIpv6Loopback() {
        assertThatThrownBy(() -> ProviderUrlGuard.validate("http://[::1]")).hasMessageContaining("已拒绝");
    }

    @Test
    @DisplayName("BYOK 只允许 HTTPS 且拒绝 localhost 与 metadata")
    void strictByokRejectsHttpLocalhostAndMetadata() throws Exception {
        DnsPinningResolver resolver = DnsPinningResolver.create(host -> new InetAddress[]{
                InetAddress.getByName("8.8.8.8")
        });

        assertThatThrownBy(() -> ProviderUrlGuard.validateByokForStorage("http://api.example.com", resolver))
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> ProviderUrlGuard.validateByokForStorage("https://localhost", resolver))
                .hasMessageContaining("已拒绝");
        assertThatThrownBy(() -> ProviderUrlGuard.validateByokForStorage("https://169.254.169.254", resolver))
                .hasMessageContaining("已拒绝");
        assertThatThrownBy(() -> ProviderUrlGuard.validateByokForStorage(
                "https://user:pass@api.example.com", resolver)).hasMessageContaining("凭据");
    }

    @Test
    @DisplayName("BYOK 域名任一解析地址非公网即拒绝")
    void strictByokRejectsMixedPublicAndPrivateDnsAnswers() throws Exception {
        DnsPinningResolver resolver = DnsPinningResolver.create(host -> new InetAddress[]{
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("10.0.0.2")
        });

        assertThatThrownBy(() -> ProviderUrlGuard.validateByokForStorage(
                "https://api.example.com", resolver)).hasMessageContaining("已拒绝");
        assertThat(resolver.getPinnedIps("api.example.com")).isEmpty();
    }

    @Test
    @DisplayName("BYOK 公网域名保存时固定全部地址，执行时校验 pin")
    void strictByokPinsPublicDnsAndRevalidatesAtExecution() throws Exception {
        var answers = new java.util.concurrent.atomic.AtomicReference<>(new InetAddress[]{
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("1.1.1.1")
        });
        DnsPinningResolver resolver = DnsPinningResolver.create(host -> answers.get());

        assertThat(ProviderUrlGuard.validateByokForStorage("https://api.example.com", resolver))
                .isEqualTo(URI.create("https://api.example.com"));
        assertThat(resolver.getPinnedIps("api.example.com"))
                .containsExactlyInAnyOrder("8.8.8.8", "1.1.1.1");
        assertThat(ProviderUrlGuard.validateByokForExecution("https://api.example.com", resolver))
                .isEqualTo(URI.create("https://api.example.com"));

        answers.set(new InetAddress[]{InetAddress.getByName("8.8.8.8")});
        assertThatThrownBy(() -> ProviderUrlGuard.validateByokForExecution(
                "https://api.example.com", resolver)).hasMessageContaining("DNS");
    }
}
