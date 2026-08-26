package com.grassland.intelligence.ai.run;

import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.ai.PlatformModelConfig;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.security.IntelligenceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 解析一次 provider 调用要用的密钥明文（同步）。任务书 #47 S2 起同时服务 BYOK 与平台凭据；
 * 2026-08-26 从 {@code AiExecutionService} 私有方法提炼，供执行环与用户态路由
 * {@link RoutedTextCompletionService} 共用——兜底语义只允许存在这一处。
 *
 * <p>三条路径：
 * <ul>
 * <li>有密文（BYOK 或平台凭据）→ 解密；无 KEK 抛 503（fail-closed，绝不退化）。</li>
 * <li>平台解析但凭据无密钥 → 回落 env {@code ai.qwen.api-key}（D1/D8 bootstrap 兜底）。</li>
 * <li>其余（DENIED 等）→ null。</li>
 * </ul>
 *
 * <p>返回的明文只活在进程内调用链里，不入日志/响应/outbox。
 */
@Component
public class ProviderKeyDecryptor {

	private final ObjectProvider<EnvelopeEncryption> encryptionProvider;
	private final PlatformModelConfig platformDefaults;

	public ProviderKeyDecryptor(ObjectProvider<EnvelopeEncryption> encryptionProvider,
			PlatformModelConfig platformDefaults) {
		this.encryptionProvider = encryptionProvider;
		this.platformDefaults = platformDefaults;
	}

	public String decryptIfNeeded(ProviderResolution provider) {
		if (provider.needsKeyDecryption()) {
			EnvelopeEncryption crypto = encryptionProvider.getIfAvailable();
			if (crypto == null) {
				throw new IntelligenceException(503, provider.isPlatform()
						? "平台凭据解密不可用：未配置 CRYPTO_KEK_BASE64"
						: "BYOK 解密不可用：未配置 CRYPTO_KEK_BASE64");
			}
			return crypto.decrypt(provider.encryptedKey());
		}
		if (!provider.isPlatform()) {
			return null;
		}
		// 平台凭据未配密钥：回落启动期 env 兜底（V46 回填出的行即此状态）。
		// 两者都没有 → 按 capability 503，不拿空 bearer 去打上游换一个语义模糊的 401（D8）。
		if (!platformDefaults.hasBootstrapKey()) {
			throw new IntelligenceException(503,
					"平台凭据缺失：该能力的凭据未配置密钥，且未提供 ai.qwen.api-key 兜底");
		}
		return platformDefaults.apiKey();
	}
}
