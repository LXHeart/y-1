package com.grassland.intelligence.ai;

import com.grassland.http.ManagedWebClientFactory;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 固定运营域名出站的 DNS 钉扎工厂（GL-P3-AI-001 尾巴 2026-08-21 覆盖扩展）。
 *
 * <p>
 * 面向「运营配置的固定第三方域名」（飞书开放平台、Bing 图片搜索、热点数据源等）—— 创建时经 {@link DnsPinningResolver}
 * 解析一次并固定地址，连接期不再走系统 DNS，与 平台 AI provider 路径同口径（rebinding TOCTOU
 * 窗口关闭）。不适用于：用户逐请求提交的
 * URL（{@code LinkReachabilityChecker}/{@code VideoRangeProxy}/webhook
 * 投递，无固定域可钉） 与内部 compose 服务名（容器 DNS 生命周期使钉扎有害）。
 */
public final class PinnedOutboundClients {

	private PinnedOutboundClients() {
	}

	/** 构建固定域名 WebClient：baseUrl 规范化带尾斜杠；绝对 URI 请求会覆盖 baseUrl（无害）。 */
	public static WebClient forFixedHost(Class<?> owner, String baseUrl, DnsPinningResolver dnsPinning,
			Duration responseTimeout, int maxResponseBytes) {
		URI target = URI.create(baseUrl == null ? "" : baseUrl.trim());
		String host = target.getHost();
		if (host == null || host.isBlank()) {
			throw new IllegalArgumentException("出站基址缺少 host：" + baseUrl);
		}
		List<InetAddress> addresses = new ArrayList<>(dnsPinning.getPinnedAddresses(host));
		if (addresses.isEmpty() && dnsPinning.pinDomainByDns(host)) {
			addresses.addAll(dnsPinning.getPinnedAddresses(host));
		}
		if (addresses.isEmpty()) {
			throw new IllegalStateException("出站域名无法解析固定地址：" + host);
		}
		return ManagedWebClientFactory
				.builder(owner, Duration.ofSeconds(3), responseTimeout, maxResponseBytes,
						PinnedAddressResolverGroup.forHost(host, List.copyOf(addresses)))
				.baseUrl(withTrailingSlash(target.toString())).build();
	}

	/** 解析固定域名并返回钉扎解析组（供自建 HttpClient 的调用方使用，如需保留 followRedirect）。 */
	public static PinnedAddressResolverGroup resolverFor(String baseUrl, DnsPinningResolver dnsPinning) {
		URI target = URI.create(baseUrl == null ? "" : baseUrl.trim());
		String host = target.getHost();
		List<InetAddress> addresses = new ArrayList<>(dnsPinning.getPinnedAddresses(host));
		if (addresses.isEmpty() && dnsPinning.pinDomainByDns(host)) {
			addresses.addAll(dnsPinning.getPinnedAddresses(host));
		}
		if (addresses.isEmpty()) {
			throw new IllegalStateException("出站域名无法解析固定地址：" + host);
		}
		return PinnedAddressResolverGroup.forHost(host, List.copyOf(addresses));
	}

	private static String withTrailingSlash(String value) {
		return value.endsWith("/") ? value : value + "/";
	}
}
