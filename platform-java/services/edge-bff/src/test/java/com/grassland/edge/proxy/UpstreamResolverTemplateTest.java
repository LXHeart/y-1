package com.grassland.edge.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * exact:true 模板段匹配（任务书 #107-1 C107-03 / K09.1 / TC107-03-02）。
 *
 * <p>
 * 验证：整段 {identifier} 匹配 UUID 与字面 id；空段、半段、编码斜线、点穿越拒绝； 无占位符的 exact 路由保持
 * 原全等语义；prefix 路由语义不变；method 仍先于路径判定；启动期拒绝非法模板。
 */
class UpstreamResolverTemplateTest {

	private static final URI INTELLIGENCE = URI.create("http://intelligence:8086");

	private UpstreamResolver resolver(RouteProperties... routes) {
		return new UpstreamResolver(new EdgeRoutingProperties(Map.of("intelligence", INTELLIGENCE), List.of(routes),
				EdgeRoutingProperties.FAIL_CLOSED));
	}

	@Test
	void wholeSegmentPlaceholderMatchesSimpleIds() {
		UpstreamResolver resolver = resolver(
				new RouteProperties("GET", "/api/hypit/projects/{projectId}", "intelligence", true, true));
		assertThat(resolver.resolveUpstreamName("GET", "/api/hypit/projects/11111111-1111-4111-8111-111111111111"))
				.isEqualTo("intelligence");
		assertThat(resolver.resolveUpstreamName("GET", "/api/hypit/projects/abc-DEF_123")).isEqualTo("intelligence");
	}

	@Test
	void placeholderRejectsEmptyTraversalSeparatorsAndNul() {
		UpstreamResolver resolver = resolver(
				new RouteProperties("GET", "/api/hypit/projects/{projectId}", "intelligence", true, true));
		assertThat(resolver.resolveUpstreamName("GET", "/api/hypit/projects/"))
				.isEqualTo(EdgeRoutingProperties.FAIL_CLOSED);
		assertThat(resolver.resolveUpstreamName("GET", "/api/hypit/projects/.."))
				.isEqualTo(EdgeRoutingProperties.FAIL_CLOSED);
		assertThat(resolver.resolveUpstreamName("GET", "/api/hypit/projects/."))
				.isEqualTo(EdgeRoutingProperties.FAIL_CLOSED);
		assertThat(resolver.resolveUpstreamName("GET", "/api/hypit/projects/a%2Fb"))
				.isEqualTo(EdgeRoutingProperties.FAIL_CLOSED);
		assertThat(resolver.resolveUpstreamName("GET", "/api/hypit/projects/a\\b"))
				.isEqualTo(EdgeRoutingProperties.FAIL_CLOSED);
	}

	@Test
	void segmentCountAndStaticSegmentsMustMatchExactly() {
		UpstreamResolver resolver = resolver(
				new RouteProperties("GET", "/api/hypit/builds/{buildId}/cancel", "intelligence", true, true));
		assertThat(resolver.resolveUpstreamName("GET", "/api/hypit/builds/b1/cancel/extra"))
				.isEqualTo(EdgeRoutingProperties.FAIL_CLOSED);
		assertThat(resolver.resolveUpstreamName("GET", "/api/hypit/builds/b1"))
				.isEqualTo(EdgeRoutingProperties.FAIL_CLOSED);
		assertThat(resolver.resolveUpstreamName("GET", "/api/other/builds/b1/cancel"))
				.isEqualTo(EdgeRoutingProperties.FAIL_CLOSED);
		assertThat(resolver.resolveUpstreamName("GET", "/api/hypit/builds/b1/cancel")).isEqualTo("intelligence");
	}

	@Test
	void methodStillGatesBeforePathSemantics() {
		UpstreamResolver resolver = resolver(
				new RouteProperties("POST", "/api/hypit/projects/{projectId}/check", "intelligence", true, true));
		assertThat(resolver.resolveUpstreamName("GET", "/api/hypit/projects/p1/check"))
				.isEqualTo(EdgeRoutingProperties.FAIL_CLOSED);
		assertThat(resolver.resolveUpstreamName("post", "/api/hypit/projects/p1/check")).isEqualTo("intelligence");
	}

	@Test
	void exactWithoutPlaceholdersKeepsEqualitySemantics() {
		UpstreamResolver resolver = resolver(
				new RouteProperties("GET", "/api/hypit/capabilities", "intelligence", true, true));
		assertThat(resolver.resolveUpstreamName("GET", "/api/hypit/capabilities")).isEqualTo("intelligence");
		assertThat(resolver.resolveUpstreamName("GET", "/api/hypit/capabilities/extra"))
				.isEqualTo(EdgeRoutingProperties.FAIL_CLOSED);
		assertThat(resolver.resolveUpstreamName("GET", "/api/hypit/capabilities2"))
				.isEqualTo(EdgeRoutingProperties.FAIL_CLOSED);
	}

	@Test
	void prefixRoutesKeepLegacySemantics() {
		UpstreamResolver resolver = resolver(
				new RouteProperties(null, "/api/digital-human", "intelligence", true, false));
		assertThat(resolver.resolveUpstreamName("DELETE", "/api/digital-human/sessions/s1")).isEqualTo("intelligence");
		assertThat(resolver.resolveUpstreamName("GET", "/api/digital-human")).isEqualTo("intelligence");
	}

	@Test
	void malformedTemplatesFailConfigurationAtStartup() {
		assertThatThrownBy(
				() -> resolver(new RouteProperties("GET", "/api/hypit/projects/id{id}", "intelligence", true, true)))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mixes static text with braces");
		assertThatThrownBy(() -> resolver(
				new RouteProperties("GET", "/api/hypit/projects/{bad-name}", "intelligence", true, true)))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whole-segment");
	}
}
