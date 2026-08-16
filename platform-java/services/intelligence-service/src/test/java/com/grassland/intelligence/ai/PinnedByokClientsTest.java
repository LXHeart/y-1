package com.grassland.intelligence.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 共享 BYOK 固定连接工厂的执行侧校验单测。DNS 解析全部走受控 HostResolver，
 * 不触网；「域名解析型内网目标」正是本工厂要堵的写入路径（私网字面量）缺口。
 */
@DisplayName("PinnedByokClients")
class PinnedByokClientsTest {

    private static DnsPinningResolver resolverResolving(String host, String... ips)
            throws UnknownHostException {
        DnsPinningResolver resolver = DnsPinningResolver.create(
                name -> host.equals(name) ? addresses(ips) : addresses("203.0.113.10"));
        // 预 pin 首地址之外的常规状态由 validateByokForExecution 自行建立；这里只需解析函数。
        return resolver;
    }

    private static InetAddress[] addresses(String... ips) throws UnknownHostException {
        InetAddress[] result = new InetAddress[ips.length];
        for (int i = 0; i < ips.length; i++) {
            result[i] = InetAddress.getByName(ips[i]);
        }
        return result;
    }

    @Test
    @DisplayName("http base-url 被拒绝（BYOK 执行侧要求 HTTPS）")
    void rejectsHttp() {
        DnsPinningResolver resolver = DnsPinningResolver.create();
        assertThatThrownBy(() -> PinnedByokClients.forBaseUrl("http://provider.example", resolver))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    @DisplayName("私网 IP 字面量被拒绝（无 DNS 参与也能挡）")
    void rejectsPrivateLiteral() {
        DnsPinningResolver resolver = DnsPinningResolver.create();
        assertThatThrownBy(() -> PinnedByokClients.forBaseUrl("https://10.1.2.3", resolver))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内网");
    }

    @Test
    @DisplayName("域名解析到私网地址被拒绝——写入路径字面量校验堵不住的缺口")
    void rejectsDomainResolvingToPrivateAddress() throws Exception {
        DnsPinningResolver resolver = resolverResolving("intranet.example", "192.168.1.10");
        assertThatThrownBy(() -> PinnedByokClients.forBaseUrl("https://intranet.example", resolver))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内网");
    }

    @Test
    @DisplayName("合法 https 公网目标构建 WebClient 并固定全部地址")
    void buildsPinnedClientForPublicTarget() throws Exception {
        DnsPinningResolver resolver = resolverResolving("provider.example", "203.0.113.10", "203.0.113.11");
        var client = PinnedByokClients.forBaseUrl("https://provider.example/v1", resolver);
        assertThat(client).isNotNull();
        assertThat(resolver.getPinnedIps("provider.example"))
                .containsExactlyInAnyOrder("203.0.113.10", "203.0.113.11");
    }

    @Test
    @DisplayName("已固定地址与当前解析不一致时拒绝（DNS rebinding 防护）")
    void rejectsWhenPinnedSetNoLongerMatches() throws Exception {
        DnsPinningResolver resolver = resolverResolving("provider.example", "203.0.113.10");
        resolver.pinDomain("provider.example", java.util.Set.of("198.51.100.99"));
        assertThatThrownBy(() -> PinnedByokClients.forBaseUrl("https://provider.example", resolver))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不一致");
    }
}
