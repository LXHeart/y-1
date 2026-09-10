package com.grassland.intelligence.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.articleimage.BingImageSearchClient;
import com.grassland.intelligence.imageanalysis.FeishuClient;
import io.netty.util.concurrent.DefaultEventExecutor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PinnedOutboundClientsTest {
	private static final String HOST = "optional-outbound.invalid";

	@Test
	void optionalClientsConstructWithoutResolvingDns() {
		AtomicInteger calls = new AtomicInteger();
		DnsPinningResolver dns = DnsPinningResolver.create(host -> {
			calls.incrementAndGet();
			throw new UnknownHostException("DNS temporarily unavailable");
		});
		assertThatCode(() -> {
			new FeishuClient(30000, dns);
			new BingImageSearchClient("https://" + HOST + "/images/search", 15000, dns);
			PinnedOutboundClients.forFixedHost(getClass(), "https://" + HOST, dns, Duration.ofSeconds(5), 1024);
		}).doesNotThrowAnyException();
		assertThat(calls).hasValue(0);
	}

	@Test
	void failedResolutionRetriesAndSuccessfulAddressesStayPinned() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		DnsPinningResolver dns = DnsPinningResolver.create(host -> {
			if (calls.incrementAndGet() == 1)
				throw new UnknownHostException("temporary failure");
			return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
		});
		var group = PinnedOutboundClients.resolverFor("https://" + HOST, dns);
		var executor = new DefaultEventExecutor();
		try {
			var resolver = group.getResolver(executor);
			assertThatThrownBy(() -> resolver.resolve(address(HOST, 443)).get(5, TimeUnit.SECONDS))
					.hasCauseInstanceOf(IllegalStateException.class);
			assertThat(resolver.resolve(address(HOST, 443)).get(5, TimeUnit.SECONDS).getAddress().getHostAddress())
					.isEqualTo("8.8.8.8");
			dns.pinDomain(HOST, java.util.Set.of("8.8.4.4"));
			assertThat(resolver.resolveAll(address(HOST, 8443)).get(5, TimeUnit.SECONDS)).allSatisfy(result -> {
				assertThat(result.getAddress().getHostAddress()).isEqualTo("8.8.8.8");
				assertThat(result.getPort()).isEqualTo(8443);
			});
			assertThat(calls).hasValue(2);
		} finally {
			group.close();
			executor.shutdownGracefully().sync();
		}
	}

	@Test
	void unexpectedHostsAreRejectedBeforeResolvingDns() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		DnsPinningResolver dns = DnsPinningResolver.create(host -> {
			calls.incrementAndGet();
			return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
		});
		var group = PinnedOutboundClients.resolverFor("https://" + HOST, dns);
		var executor = new DefaultEventExecutor();
		try {
			var resolver = group.getResolver(executor);
			assertThatThrownBy(() -> resolver.resolve(address("other.invalid", 443)).get(5, TimeUnit.SECONDS))
					.hasCauseInstanceOf(SecurityException.class);
			assertThatThrownBy(() -> resolver.resolveAll(address("other.invalid", 443)).get(5, TimeUnit.SECONDS))
					.hasCauseInstanceOf(SecurityException.class);
			assertThat(calls).hasValue(0);
		} finally {
			group.close();
			executor.shutdownGracefully().sync();
		}
	}

	@Test
	void concurrentLookupsShareDnsAndDoNotBlockTheEventExecutor() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		var group = PinnedAddressResolverGroup.forDeferredHost(HOST, () -> {
			calls.incrementAndGet();
			started.countDown();
			try {
				if (!release.await(5, TimeUnit.SECONDS))
					throw new IllegalStateException("test DNS release timed out");
				return java.util.List.of(InetAddress.getLoopbackAddress());
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(error);
			}
		});
		var executor = new DefaultEventExecutor();
		try {
			var resolver = group.getResolver(executor);
			var first = executor.submit(() -> resolver.resolve(address(HOST, 443))).get(2, TimeUnit.SECONDS);
			assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
			var second = resolver.resolve(address(HOST, 8443));
			assertThat(executor.submit(() -> "responsive").get(2, TimeUnit.SECONDS)).isEqualTo("responsive");
			release.countDown();
			assertThat(first.get(5, TimeUnit.SECONDS).getPort()).isEqualTo(443);
			assertThat(second.get(5, TimeUnit.SECONDS).getPort()).isEqualTo(8443);
			assertThat(calls).hasValue(1);
		} finally {
			release.countDown();
			group.close();
			executor.shutdownGracefully().sync();
		}
	}

	@Test
	void webClientConnectsToPinnedIpAndKeepsOriginalHost() throws Exception {
		WireMockServer upstream = new WireMockServer(0);
		upstream.start();
		AtomicInteger calls = new AtomicInteger();
		DnsPinningResolver dns = DnsPinningResolver.create(host -> {
			calls.incrementAndGet();
			return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
		});
		try {
			upstream.stubFor(get(urlEqualTo("/ping"))
					.willReturn(aResponse().withBody("pong").withHeader("Connection", "close")));
			var client = PinnedOutboundClients.forFixedHost(getClass(), "http://" + HOST + ":" + upstream.port(), dns,
					Duration.ofSeconds(5), 1024);
			assertThat(calls).hasValue(0);
			for (int i = 0; i < 2; i++) {
				assertThat(client.get().uri("/ping").retrieve().bodyToMono(String.class).block(Duration.ofSeconds(10)))
						.isEqualTo("pong");
			}
			assertThat(calls).hasValue(1);
			upstream.verify(2,
					getRequestedFor(urlEqualTo("/ping")).withHeader("Host", equalTo(HOST + ":" + upstream.port())));
		} finally {
			upstream.stop();
		}
	}

	private static InetSocketAddress address(String host, int port) {
		return InetSocketAddress.createUnresolved(host, port);
	}
}
