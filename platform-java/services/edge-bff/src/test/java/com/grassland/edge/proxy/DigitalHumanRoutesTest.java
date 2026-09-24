package com.grassland.edge.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 数字人域路由与关闭模式（任务书 #105B C105B-02 / TC105B-02 配套 / 共享契约 K03、K10）。
 *
 * <p>
 * 验证：DH 前缀默认关闭（fail-closed，resolve 不到上游）；打开后控制 API 全方法经 intelligence； 治理端
 * /api/admin/digital-human 精确前缀不抢其它 /api/admin/*；上游只能引用已声明 upstream 名， 不能写任意
 * URL（构造期即拒）。
 */
class DigitalHumanRoutesTest {

	private static final URI INTELLIGENCE = URI.create("http://intelligence:8086");
	private static final URI IDENTITY = URI.create("http://identity:8082");

	private EdgeRoutingProperties properties(boolean digitalHumanEnabled, boolean adminEnabled) {
		return new EdgeRoutingProperties(Map.of("intelligence", INTELLIGENCE, "identity", IDENTITY),
				List.of(new RouteProperties(null, "/api/digital-human", "intelligence", digitalHumanEnabled),
						new RouteProperties(null, "/api/admin/digital-human", "intelligence", adminEnabled)),
				EdgeRoutingProperties.FAIL_CLOSED);
	}

	@Test
	void digitalHumanPrefixFailsClosedWhenFlagOff() {
		UpstreamResolver resolver = new UpstreamResolver(properties(false, false));
		for (String method : new String[]{"GET", "POST", "PATCH", "DELETE"}) {
			assertThat(resolver.resolveUpstreamName(method, "/api/digital-human/catalog"))
					.as("%s /api/digital-human/catalog 关闭态", method).isEqualTo(EdgeRoutingProperties.FAIL_CLOSED);
			assertThat(resolver.resolve(method, "/api/digital-human/sessions/s-1/events")).isNull();
		}
		assertThat(resolver.isInternalUpstream("GET", "/api/digital-human/catalog")).isFalse();
	}

	@Test
	void digitalHumanPrefixRoutesAllControlMethodsWhenEnabled() {
		UpstreamResolver resolver = new UpstreamResolver(properties(true, true));
		assertThat(resolver.resolveUpstreamName("GET", "/api/digital-human/catalog")).isEqualTo("intelligence");
		assertThat(resolver.resolveUpstreamName("POST", "/api/digital-human/profiles")).isEqualTo("intelligence");
		assertThat(resolver.resolveUpstreamName("PATCH", "/api/digital-human/profiles/p-1")).isEqualTo("intelligence");
		assertThat(resolver.resolveUpstreamName("DELETE", "/api/digital-human/sessions/s-1/transcript"))
				.isEqualTo("intelligence");
		assertThat(resolver.resolveUpstreamName("GET", "/api/digital-human/sessions/s-1/events"))
				.isEqualTo("intelligence");
		assertThat(resolver.isInternalUpstream("POST", "/api/digital-human/sessions")).isTrue();
	}

	@Test
	void adminPrefixIsExactAndDoesNotCaptureOtherAdminFamilies() {
		UpstreamResolver resolver = new UpstreamResolver(properties(false, true));
		// K10 ADMIN01～06 六端点全部经治理前缀到 intelligence（#105G C105G-03）。
		assertThat(resolver.resolveUpstreamName("GET", "/api/admin/digital-human/config")).isEqualTo("intelligence");
		assertThat(resolver.resolveUpstreamName("PUT", "/api/admin/digital-human/config")).isEqualTo("intelligence");
		assertThat(resolver.resolveUpstreamName("GET", "/api/admin/digital-human/sessions")).isEqualTo("intelligence");
		assertThat(resolver.resolveUpstreamName("POST", "/api/admin/digital-human/sessions/s-1/terminate"))
				.isEqualTo("intelligence");
		assertThat(resolver.resolveUpstreamName("GET", "/api/admin/digital-human/invocations"))
				.isEqualTo("intelligence");
		assertThat(resolver.resolveUpstreamName("POST", "/api/admin/digital-human/invocations/i-1/reconcile"))
				.isEqualTo("intelligence");
		// 其它 /api/admin/* 家族不被数字人前缀抢占（fail-closed 兜底）。
		assertThat(resolver.resolveUpstreamName("GET", "/api/admin/users"))
				.isEqualTo(EdgeRoutingProperties.FAIL_CLOSED);
		assertThat(resolver.resolveUpstreamName("GET", "/api/admin/digital-humanish/config")).as("前缀匹配按路径段，不吞近似名")
				.isEqualTo(EdgeRoutingProperties.FAIL_CLOSED);
		// 控制域 flag 与治理域 flag 相互独立：治理开、控制关。
		assertThat(resolver.resolveUpstreamName("GET", "/api/digital-human/catalog"))
				.isEqualTo(EdgeRoutingProperties.FAIL_CLOSED);
	}

	@Test
	void upstreamMustBeDeclaredNameNotArbitraryUrl() {
		// route.upstream 只能引用 edge.upstreams 已声明名：任意 URL 在属性构造期即被拒绝。
		assertThatThrownBy(() -> new EdgeRoutingProperties(Map.of("intelligence", INTELLIGENCE),
				List.of(new RouteProperties(null, "/api/digital-human", "https://evil.example/v1", true)),
				EdgeRoutingProperties.FAIL_CLOSED)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("evil.example");
	}
}
