package com.grassland.intelligence.ai;

import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * 固定地址解析器（GL-P3-AI-001 Phase 2，自 TextCompletionClient 上提为共享组件）。
 *
 * <p>URI 仍保留原始 hostname（Host header / TLS SNI），只替换 Netty 的地址解析结果，
 * 确保校验后的请求不会再次走系统 DNS，关闭 DNS rebinding 的 TOCTOU 窗口。
 * 解析时还会拒绝与预期不符的 host，防止连接被重定向到其它目标。
 */
public final class PinnedAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    private final String expectedHost;
    private final List<InetAddress> addresses;

    PinnedAddressResolverGroup(String expectedHost, List<InetAddress> addresses) {
        this.expectedHost = expectedHost;
        this.addresses = List.copyOf(addresses);
    }

    /** 平台 provider 出站固定入口（GL-P3-AI-001 尾巴）：host 校验 + 地址集合固定。 */
    public static PinnedAddressResolverGroup forHost(String expectedHost, List<InetAddress> addresses) {
        if (expectedHost == null || expectedHost.isBlank() || addresses == null || addresses.isEmpty()) {
            throw new IllegalArgumentException("expectedHost and addresses are required");
        }
        return new PinnedAddressResolverGroup(expectedHost, addresses);
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
        return new AbstractAddressResolver<>(executor, InetSocketAddress.class) {
            @Override
            protected boolean doIsResolved(InetSocketAddress address) {
                return false;
            }

            @Override
            protected void doResolve(InetSocketAddress unresolved, Promise<InetSocketAddress> promise) {
                try {
                    promise.setSuccess(resolveOne(unresolved));
                } catch (RuntimeException error) {
                    promise.setFailure(error);
                }
            }

            @Override
            protected void doResolveAll(
                    InetSocketAddress unresolved, Promise<List<InetSocketAddress>> promise) {
                try {
                    validateHost(unresolved);
                    List<InetSocketAddress> resolved = addresses.stream()
                            .map(address -> new InetSocketAddress(address, unresolved.getPort()))
                            .toList();
                    promise.setSuccess(resolved);
                } catch (RuntimeException error) {
                    promise.setFailure(error);
                }
            }

            private InetSocketAddress resolveOne(InetSocketAddress unresolved) {
                validateHost(unresolved);
                return new InetSocketAddress(addresses.getFirst(), unresolved.getPort());
            }

            private void validateHost(InetSocketAddress unresolved) {
                if (!expectedHost.equalsIgnoreCase(unresolved.getHostString())) {
                    throw new SecurityException("Unexpected outbound host");
                }
            }
        };
    }
}
