package com.grassland.intelligence.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.ai.DnsPinningResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * {@code listModelsAt} 出站口径回归（任务书 #88 D-05 平移）：SSRF/DNS 钉扎拒绝必须以 400
 * 指向配置且先于任何 HTTP 请求。旧用户侧 listModels(accountId,feature)/verifyModel 已随 #88 退役，
 * 本文件口径平移到治理台平台凭据消费的 {@code listModelsAt}（其此前无任何直接测试覆盖）。
 */
class ModelListingServiceTest {

	private ModelListingService service(DnsPinningResolver resolver) {
		return new ModelListingService(resolver);
	}

	private static DnsPinningResolver resolverResolving(String host, String ip) throws UnknownHostException {
		return DnsPinningResolver.create(name -> host.equals(name)
				? new InetAddress[]{InetAddress.getByName(ip)}
				: new InetAddress[]{InetAddress.getByName("203.0.113.10")});
	}

	@org.junit.jupiter.api.Test
	void listModelsAtRejectsHttpBaseUrlAs400() {
		ModelListingService service = service(DnsPinningResolver.create());

		assertThatThrownBy(() -> service.listModelsAt("http://provider.example", "sk-x").block())
				.isInstanceOf(IntelligenceException.class)
				.hasMessageContaining("HTTPS").extracting(e -> ((IntelligenceException) e).status()).isEqualTo(400);
	}

	@org.junit.jupiter.api.Test
	void listModelsAtRejectsDomainResolvingToIntranet() throws Exception {
		ModelListingService service = service(resolverResolving("intranet.example", "10.0.0.8"));

		assertThatThrownBy(() -> service.listModelsAt("https://intranet.example", "sk-x").block())
				.isInstanceOf(IntelligenceException.class)
				.hasMessageContaining("内网").extracting(e -> ((IntelligenceException) e).status()).isEqualTo(400);
	}

	@org.junit.jupiter.api.Test
	void listModelsAtRequiresBaseUrl() {
		ModelListingService service = service(DnsPinningResolver.create());

		assertThatThrownBy(() -> service.listModelsAt("  ", "sk-x").block())
				.isInstanceOf(IntelligenceException.class)
				.hasMessageContaining("baseUrl").extracting(e -> ((IntelligenceException) e).status()).isEqualTo(400);
	}

	@org.junit.jupiter.api.Test
	void listModelsAtRequiresApiKey() {
		ModelListingService service = service(DnsPinningResolver.create());

		assertThatThrownBy(() -> service.listModelsAt("https://provider.example", "").block())
				.isInstanceOf(IntelligenceException.class)
				.hasMessageContaining("密钥").extracting(e -> ((IntelligenceException) e).status()).isEqualTo(400);
		assertThat(service).isNotNull();
	}
}
