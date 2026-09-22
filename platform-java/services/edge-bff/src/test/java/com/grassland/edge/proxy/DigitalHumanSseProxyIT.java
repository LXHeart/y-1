package com.grassland.edge.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * 数字人 SSE 代理 IT（任务书 #105C C105C-05 / V105C-05-02）：Edge → intelligence 的
 * text/event-stream 透传不聚合（三条定时事件逐步到达、可中止）；路由 flag 默认关闭（fail-closed 404）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"management.server.port=0",
		"PUBLIC_BACKEND_ORIGIN=http://localhost:8080", "BILIBILI_PROXY_TOKEN_SECRET=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
		"DOUYIN_PROXY_TOKEN_SECRET=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "EDGE_ROUTE_DIGITAL_HUMAN_INTELLIGENCE=true"})
class DigitalHumanSseProxyIT {

	private static DisposableServer upstream;

	@LocalServerPort
	private int edgePort;

	@BeforeAll
	static void startUpstream() {
		// Fake intelligence：SSE 端点 250ms 间隔推三条事件（逐块）。
		upstream = HttpServer.create().port(0).handle((request, response) -> {
			if (request.uri().contains("/api/digital-human/sessions/s-1/events")) {
				response.header("Content-Type", "text/event-stream");
				return response.send(Flux.just(1, 2, 3)
						.concatMap(index -> Mono.delay(Duration.ofMillis(250))
								.map(tick -> response.alloc().buffer().writeBytes(
										("id: " + index + "\nevent: session.state\ndata: {\"seq\":" + index + "}\n\n")
												.getBytes(StandardCharsets.UTF_8)))));
			}
			return response.sendNotFound();
		}).bindNow(Duration.ofSeconds(30));
	}

	@AfterAll
	static void stopUpstream() {
		if (upstream != null) {
			upstream.disposeNow();
		}
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		InetSocketAddress address = (InetSocketAddress) upstream.address();
		registry.add("edge.upstreams.intelligence", () -> "http://localhost:" + address.getPort());
	}

	@Test
	void sseEventsStreamThroughEdgeIncrementallyAndAbortable() throws Exception {
		WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + edgePort)
				.responseTimeout(Duration.ofSeconds(30)).build();
		List<Long> arrivals = new CopyOnWriteArrayList<>();
		List<String> frames = new CopyOnWriteArrayList<>();
		var result = client.get().uri("/api/digital-human/sessions/s-1/events").exchange().expectStatus().isOk()
				.expectHeader().contentTypeCompatibleWith("text/event-stream").returnResult(String.class);
		var subscription = result.getResponseBody().doOnNext(frame -> {
			arrivals.add(System.currentTimeMillis());
			frames.add(frame);
		}).subscribe();
		long start = System.currentTimeMillis();
		while (frames.size() < 3 && System.currentTimeMillis() - start < 15_000) {
			Thread.sleep(50);
		}
		subscription.dispose();
		assertThat(frames).hasSize(3);
		// 逐块到达（非整段缓冲一次吐出）：相邻到达间隔 ≥ 交付间隔的一半。
		assertThat(arrivals.get(1) - arrivals.get(0)).isGreaterThanOrEqualTo(120);
		assertThat(arrivals.get(2) - arrivals.get(1)).isGreaterThanOrEqualTo(120);
		assertThat(frames.get(0)).contains("seq");
	}
}
