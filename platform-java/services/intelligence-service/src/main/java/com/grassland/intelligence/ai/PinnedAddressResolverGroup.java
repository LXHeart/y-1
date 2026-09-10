package com.grassland.intelligence.ai;

import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 固定地址解析器（GL-P3-AI-001 Phase 2，自 TextCompletionClient 上提为共享组件）。
 *
 * <p>
 * URI 仍保留原始 hostname（Host header / TLS SNI），只替换 Netty 的地址解析结果， 确保校验后的请求不会再次走系统
 * DNS，关闭 DNS rebinding 的 TOCTOU 窗口。 解析时还会拒绝与预期不符的 host，防止连接被重定向到其它目标。
 */
public final class PinnedAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

	private final String expectedHost;
	private final Mono<List<InetAddress>> addresses;

	PinnedAddressResolverGroup(String expectedHost, List<InetAddress> addresses) {
		this(expectedHost, Mono.just(List.copyOf(addresses)));
	}

	private PinnedAddressResolverGroup(String expectedHost, Mono<List<InetAddress>> addresses) {
		this.expectedHost = expectedHost;
		this.addresses = addresses;
	}

	/** 平台 provider 出站固定入口（GL-P3-AI-001 尾巴）：host 校验 + 地址集合固定。 */
	public static PinnedAddressResolverGroup forHost(String expectedHost, List<InetAddress> addresses) {
		if (expectedHost == null || expectedHost.isBlank() || addresses == null || addresses.isEmpty()) {
			throw new IllegalArgumentException("expectedHost and addresses are required");
		}
		return new PinnedAddressResolverGroup(expectedHost, addresses);
	}

	/**
	 * 固定运营域名在首次连接时解析；阻塞 DNS 在后台线程执行，失败不缓存，成功地址固定到解析器关闭。 已经过公网校验的 BYOK/provider
	 * 地址仍使用 forHost，不延后其安全校验。
	 */
	public static PinnedAddressResolverGroup forDeferredHost(String expectedHost,
			Supplier<List<InetAddress>> supplier) {
		if (expectedHost == null || expectedHost.isBlank() || supplier == null) {
			throw new IllegalArgumentException("expectedHost and address supplier are required");
		}
		Mono<List<InetAddress>> resolved = Mono.fromCallable(() -> {
			List<InetAddress> values = List.copyOf(supplier.get());
			if (values.isEmpty()) {
				throw new IllegalStateException("No pinned outbound addresses");
			}
			return values;
		}).subscribeOn(Schedulers.boundedElastic()).timeout(Duration.ofSeconds(3))
				// cacheInvalidateIf 只缓存成功值；DNS 错误/超时后的下一次连接会重新解析。
				.cacheInvalidateIf(ignored -> false);
		return new PinnedAddressResolverGroup(expectedHost, resolved);
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
					validateHost(unresolved);
					complete(promise,
							addresses.map(values -> new InetSocketAddress(values.getFirst(), unresolved.getPort())));
				} catch (RuntimeException error) {
					promise.tryFailure(error);
				}
			}

			@Override
			protected void doResolveAll(InetSocketAddress unresolved, Promise<List<InetSocketAddress>> promise) {
				try {
					validateHost(unresolved);
					complete(promise, addresses.map(values -> values.stream()
							.map(address -> new InetSocketAddress(address, unresolved.getPort())).toList()));
				} catch (RuntimeException error) {
					promise.tryFailure(error);
				}
			}

			private <T> void complete(Promise<T> promise, Mono<T> resolution) {
				if (promise.isCancelled())
					return;
				Disposable pending = resolution.subscribe(promise::trySuccess, promise::tryFailure);
				promise.addListener(future -> {
					if (future.isCancelled())
						pending.dispose();
				});
			}

			private void validateHost(InetSocketAddress unresolved) {
				if (!expectedHost.equalsIgnoreCase(unresolved.getHostString())) {
					throw new SecurityException("Unexpected outbound host");
				}
			}
		};
	}
}
