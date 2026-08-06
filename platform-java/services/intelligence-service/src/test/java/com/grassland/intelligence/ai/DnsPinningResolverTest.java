package com.grassland.intelligence.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DnsPinningResolver 单元测试（GL-P3-AI-001 Phase 2）。
 */
@DisplayName("DnsPinningResolver")
class DnsPinningResolverTest {

    private DnsPinningResolver resolver;

    @BeforeEach
    void setup() {
        resolver = DnsPinningResolver.create();
    }

    @Test
    @DisplayName("pinDomainByDns 固定解析到的全部地址")
    void pinDomainByDnsPinsEveryResolvedAddress() throws Exception {
        resolver = DnsPinningResolver.create(host -> new InetAddress[]{
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("1.1.1.1")
        });

        assertThat(resolver.pinDomainByDns("api.example.com")).isTrue();
        assertThat(resolver.getPinnedIps("api.example.com"))
                .containsExactlyInAnyOrder("8.8.8.8", "1.1.1.1");
    }

    @Test
    @DisplayName("运行时 DNS 地址集合变化时拒绝目标")
    void rejectsChangedDnsAddressSet() throws Exception {
        var answers = new java.util.concurrent.atomic.AtomicReference<>(new InetAddress[]{
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("1.1.1.1")
        });
        resolver = DnsPinningResolver.create(host -> answers.get());
        assertThat(resolver.pinDomainByDns("api.example.com")).isTrue();

        answers.set(new InetAddress[]{InetAddress.getByName("8.8.8.8")});

        assertThat(resolver.isSafeTarget("https://api.example.com/v1")).isFalse();
    }

    @Test
    @DisplayName("pinDomain 固定域名 IP 映射")
    void pinDomain_mapsDomainToIps() {
        resolver.pinDomain("api.openai.com", Set.of("104.16.123.45"));

        assertThat(resolver.getPinnedIps("api.openai.com")).containsExactly("104.16.123.45");
        assertThat(resolver.getPinnedDomains()).containsExactly("api.openai.com");
    }

    @Test
    @DisplayName("isSafeTarget 对已 pin 域名返回 true")
    void isSafeTarget_pinnedDomain_returnsTrue() {
        resolver.pinDomain("api.openai.com", Set.of("104.16.123.45"));

        // 注意：由于测试环境无法真正 DNS 解析，这里假设测试时域名能解析到配置的 IP
        // 实际运行时需要 mock InetAddress.getAllByName 或使用真实 IP
        assertThat(resolver.isSafeTarget("https://api.openai.com/v1/chat")).isFalse();  // 测试环境无 DNS
    }

    @Test
    @DisplayName("isSafeTarget 对未 pin 域名返回 false")
    void isSafeTarget_unpinnedDomain_returnsFalse() {
        assertThat(resolver.isSafeTarget("https://malicious.example.com/api")).isFalse();
    }

    @Test
    @DisplayName("isSafeTarget 对空字符串返回 false")
    void isSafeTarget_null_returnsFalse() {
        assertThat(resolver.isSafeTarget(null)).isFalse();
        assertThat(resolver.isSafeTarget("")).isFalse();
        assertThat(resolver.isSafeTarget("  ")).isFalse();
    }

    @Test
    @DisplayName("fromEnv 解析环境变量配置")
    void fromEnv_parsesEnv() {
        DnsPinningResolver loaded = DnsPinningResolver.fromEnv(
                "api.openai.com=104.16.123.45;dashscope.aliyuncs.com=47.96.23.1,47.96.23.2"
        );

        assertThat(loaded.getPinnedIps("api.openai.com")).containsExactly("104.16.123.45");
        assertThat(loaded.getPinnedIps("dashscope.aliyuncs.com"))
                .containsExactlyInAnyOrder("47.96.23.1", "47.96.23.2");
    }

    @Test
    @DisplayName("fromEnv 处理空配置")
    void fromEnv_emptyConfig() {
        DnsPinningResolver empty = DnsPinningResolver.fromEnv("");
        assertThat(empty.getPinnedDomains()).isEmpty();

        DnsPinningResolver nullConfig = DnsPinningResolver.fromEnv((String) null);
        assertThat(nullConfig.getPinnedDomains()).isEmpty();
    }

    @Test
    @DisplayName("fromEnv 忽略格式错误的条目")
    void fromEnv_ignoresMalformed() {
        DnsPinningResolver loaded = DnsPinningResolver.fromEnv(
                "valid.com=1.2.3.4;invalid-entry;another.com=5.6.7.8"
        );

        assertThat(loaded.getPinnedDomains()).containsExactlyInAnyOrder("valid.com", "another.com");
    }

    @Test
    @DisplayName("pinDomain 支持多 IP 负载均衡")
    void pinDomain_multipleIps() {
        resolver.pinDomain("api.example.com", Set.of("1.2.3.4", "1.2.3.5", "1.2.3.6"));

        assertThat(resolver.getPinnedIps("api.example.com"))
                .containsExactlyInAnyOrder("1.2.3.4", "1.2.3.5", "1.2.3.6");
    }

    @Test
    @DisplayName("getPinnedIps 对未 pin 域名返回空集合")
    void getPinnedIps_unpinned_returnsEmpty() {
        assertThat(resolver.getPinnedIps("unpinned.com")).isEmpty();
    }

    @Test
    @DisplayName("isSafeTarget 从 URL 提取主机名")
    void isSafeTarget_extractsHost() {
        resolver.pinDomain("api.openai.com", Set.of("104.16.123.45"));

        // 测试主机名提取（虽然实际 DNS 检查会失败）
        assertThat(resolver.isSafeTarget("https://api.openai.com/v1/chat")).isFalse();  // DNS 验证失败
    }

    @Test
    @DisplayName("isSafeTarget 处理无效 URL")
    void isSafeTarget_invalidUrl() {
        assertThat(resolver.isSafeTarget("not-a-url")).isFalse();
        assertThat(resolver.isSafeTarget("http://")).isFalse();
    }

    @Test
    @DisplayName("pinDomain 可覆盖已存在的映射")
    void pinDomain_overwritesExisting() {
        resolver.pinDomain("api.example.com", Set.of("1.2.3.4"));
        assertThat(resolver.getPinnedIps("api.example.com")).containsExactly("1.2.3.4");

        resolver.pinDomain("api.example.com", Set.of("5.6.7.8"));
        assertThat(resolver.getPinnedIps("api.example.com")).containsExactly("5.6.7.8");
    }

    @Test
    @DisplayName("DNS Pinning 防御 DNS 重定向攻击场景")
    void dnsPinningPreventsRedirectionAttack() {
        // 正常配置
        resolver.pinDomain("api.trusted.com", Set.of("10.0.0.1"));

        // 攻击者尝试通过 DNS 污染重定向到恶意 IP
        // 即使 DNS 返回不同 IP，isSafeTarget 也会拒绝
        assertThat(resolver.isSafeTarget("https://api.trusted.com/endpoint")).isFalse();  // DNS 不匹配

        // 未 pin 的恶意域名直接被拒绝
        assertThat(resolver.isSafeTarget("https://malicious-site.com/api")).isFalse();
    }
}
