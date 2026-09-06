package com.grassland.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 任务书 #86 C-01：audience 枚举与 origin 匹配器单元（直接 new 组件传配置串，纯 JUnit）。
 * 匹配是 scheme+host+port 的精确字符串相等（无通配、无后缀匹配）；Origin 缺失放行；
 * audience 未配置 origin 列表（空串）放行。
 */
class CrossAppAudienceOriginsTest {
	/** 含空格与空段的逗号分隔配置——必须被 trim、去空。 */
	private final CrossAppAudienceOrigins configured = new CrossAppAudienceOrigins(
			"http://a.test, http://b.test", "http://ai.test");
	private final CrossAppAudienceOrigins unconfigured = new CrossAppAudienceOrigins("", "");

	@Test
	void validAudienceAcceptsOnlyExactLowercaseEnum() {
		assertThat(CrossAppAudienceOrigins.validAudience("ai")).isTrue();
		assertThat(CrossAppAudienceOrigins.validAudience("grassland")).isTrue();
		assertThat(CrossAppAudienceOrigins.validAudience(null)).isFalse();
		assertThat(CrossAppAudienceOrigins.validAudience("")).isFalse();
		assertThat(CrossAppAudienceOrigins.validAudience("AI")).isFalse();
		assertThat(CrossAppAudienceOrigins.validAudience("ops")).isFalse();
	}

	@Test
	void originsTrimsAndDropsEmptyEntries() {
		assertThat(configured.origins("grassland")).containsExactly("http://a.test", "http://b.test");
		assertThat(configured.origins("ai")).containsExactly("http://ai.test");
		assertThat(unconfigured.origins("grassland")).isEmpty();
		assertThat(configured.origins("ops")).isEmpty();
	}

	@Test
	void allowsMatchesExactOriginOnly() {
		assertThat(configured.allows("grassland", "http://a.test")).isTrue();
		assertThat(configured.allows("grassland", "http://b.test")).isTrue();
		// 精确匹配证据：端口、域名、scheme 任一差异都拒绝
		assertThat(configured.allows("grassland", "http://a.test:80")).isFalse();
		assertThat(configured.allows("grassland", "http://evil.test")).isFalse();
		assertThat(configured.allows("grassland", "https://a.test")).isFalse();
	}

	@Test
	void allowsPermitsMissingOriginHeaderAndUnconfiguredAudience() {
		// Origin 缺失放行（部分浏览器同源 POST 不带 Origin）
		assertThat(configured.allows("grassland", null)).isTrue();
		assertThat(configured.allows("grassland", "  ")).isTrue();
		// 未配置列表（dev 同源形态）放行
		assertThat(unconfigured.allows("grassland", "http://evil.test")).isTrue();
		// 未知 audience 无列表 → 放行（枚举校验在上游 400 拦截）
		assertThat(configured.allows("ops", "http://evil.test")).isTrue();
	}

	@Test
	void audienceOfResolvesConfiguredOriginOrUnknown() {
		assertThat(configured.audienceOf("http://ai.test")).isEqualTo("ai");
		assertThat(configured.audienceOf("http://a.test")).isEqualTo("grassland");
		assertThat(configured.audienceOf("http://evil.test")).isEqualTo("unknown");
		assertThat(configured.audienceOf(null)).isEqualTo("unknown");
		assertThat(configured.audienceOf("")).isEqualTo("unknown");
	}
}
