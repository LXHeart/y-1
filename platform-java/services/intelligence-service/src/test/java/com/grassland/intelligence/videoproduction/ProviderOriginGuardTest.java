package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 同站点后缀判定：CDN 兄弟域放行、两段注册域基域不放宽（SSRF 防线的边界）。 */
@DisplayName("Provider origin guard")
class ProviderOriginGuardTest {

	@Test
	void siblingSubdomainOfThreeLabelBaseIsAllowed() throws Exception {
		assertThat(ProviderOriginGuard.sameSite(new URI("https://filecdn.minimaxi.com/audio/a.mp3"),
				new URI("https://api.minimaxi.com"))).isTrue();
		assertThat(
				ProviderOriginGuard.sameSite(new URI("https://vidgen.x.ai/v/req-1.mp4"), new URI("https://api.x.ai")))
				.isTrue();
	}

	@Test
	void exactHostStillAllowedAndCaseInsensitive() throws Exception {
		assertThat(ProviderOriginGuard.sameSite(new URI("https://API.minimaxi.com/v1/x"),
				new URI("https://api.minimaxi.com"))).isTrue();
	}

	@Test
	void twoLabelRegistrableBaseStaysStrict() throws Exception {
		// fanrenapi.com 已是注册域：剥首段会得到 *.com 级误放，必须拒绝兄弟域
		assertThat(ProviderOriginGuard.sameSite(new URI("https://evil.com/a.mp3"), new URI("https://fanrenapi.com")))
				.isFalse();
		assertThat(ProviderOriginGuard.sameSite(new URI("https://cdn.fanrenapi.com/a.mp3"),
				new URI("https://fanrenapi.com"))).isFalse();
	}

	@Test
	void crossSiteAndMalformedAreRejected() throws Exception {
		assertThat(ProviderOriginGuard.sameSite(new URI("https://cdn.example.org/a.mp3"),
				new URI("https://api.minimaxi.com"))).isFalse();
		assertThat(ProviderOriginGuard.sameSite(new URI("https://x.minimaxi.com"), new URI("https://xai.com")))
				.isFalse();
		assertThat(ProviderOriginGuard.sameSite(new URI("/relative"), new URI("https://api.minimaxi.com"))).isFalse();
		assertThat(ProviderOriginGuard.isHttpScheme("ftp")).isFalse();
	}

	@Test
	void trustedOriginTableAllowsCrossSiteArtifactDomain() throws Exception {
		// MiniMax 音频在阿里云 OSS（跨站）——受信表一行放行；报错里的 origin 可直接照抄
		java.util.Set<String> trusted = java.util.Set.of("https://cloudinfra-minimax.oss-cn-beijing.aliyuncs.com:443");
		assertThat(ProviderOriginGuard.allowed(
				new URI("https://cloudinfra-minimax.oss-cn-beijing.aliyuncs.com/audio/a.mp3?sig=x"),
				new URI("https://api.minimaxi.com"), trusted)).isTrue();
		assertThat(ProviderOriginGuard.allowed(new URI("https://other-bucket.oss-cn-beijing.aliyuncs.com/a.mp3"),
				new URI("https://api.minimaxi.com"), trusted)).isFalse();
		assertThat(ProviderOriginGuard.allowed(new URI("https://filecdn.minimaxi.com/a.mp3"),
				new URI("https://api.minimaxi.com"), java.util.Set.of())).isTrue();
	}
}
