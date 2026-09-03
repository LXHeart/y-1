package com.grassland.intelligence.videoproduction;

import java.net.URI;

/**
 * provider 回传资源地址（视频成片 URL / TTS 音频 download_url）的同源判定。
 *
 * <p>
 * <b>为什么不能严格 host 相等</b>：上游把生成产物放 CDN 兄弟域——MiniMax 文件在
 * {@code *.minimaxi.com}（API 在 api.minimaxi.com）、xAI 视频在
 * {@code vidgen.x.ai}（API 在 api.x.ai）。2026-09-03 真实链路首次走通即被严格相等误杀（TTS「音频地址不在
 * provider origin 内」循环重试）。
 *
 * <p>
 * <b>规则</b>：host 相等，或基域（provider base_url 的 host）≥3 段时允许「剥掉首段后的同后缀」
 * （api.minimaxi.com → *.minimaxi.com）。两段基域（fanrenapi.com 这类已是注册域）不放宽——
 * 无公共后缀表依赖下 {@code *.com} 级误放不可接受。仅认 http/https。
 */
final class ProviderOriginGuard {

	private ProviderOriginGuard() {
	}

	static boolean isHttpScheme(String scheme) {
		return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
	}

	/** actual 与 base 同站点（相等或注册域兄弟子域）；任一 host 缺失即 false。 */
	static boolean sameSite(URI actual, URI base) {
		if (actual == null || base == null) {
			return false;
		}
		String actualHost = actual.getHost();
		String baseHost = base.getHost();
		if (actualHost == null || baseHost == null) {
			return false;
		}
		if (actualHost.equalsIgnoreCase(baseHost)) {
			return true;
		}
		String[] labels = baseHost.toLowerCase(java.util.Locale.ROOT).split("\\.");
		if (labels.length < 3) {
			return false;
		}
		String siteSuffix = String.join(".", java.util.Arrays.copyOfRange(labels, 1, labels.length));
		return actualHost.toLowerCase(java.util.Locale.ROOT).endsWith("." + siteSuffix);
	}
}
