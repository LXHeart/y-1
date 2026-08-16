package com.grassland.intelligence.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.util.concurrent.DefaultEventExecutor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PinnedAddressResolverGroup")
class PinnedAddressResolverGroupTest {

    @Test
    @DisplayName("把原 hostname 映射到固定 IP 并拒绝其他 host")
    void pinnedResolverUsesOnlyApprovedAddress() throws Exception {
        InetAddress approved = InetAddress.getByName("8.8.8.8");
        var group = new PinnedAddressResolverGroup("api.example.com", List.of(approved));
        var executor = new DefaultEventExecutor();
        try {
            var resolver = group.getResolver(executor);
            InetSocketAddress resolved = resolver.resolve(
                    InetSocketAddress.createUnresolved("api.example.com", 443)).get();
            assertThat(resolved.getAddress().getHostAddress()).isEqualTo("8.8.8.8");

            assertThatThrownBy(() -> resolver.resolve(
                    InetSocketAddress.createUnresolved("other.example.com", 443)).get())
                    .hasCauseInstanceOf(SecurityException.class);
        } finally {
            group.close();
            executor.shutdownGracefully().sync();
        }
    }
}
