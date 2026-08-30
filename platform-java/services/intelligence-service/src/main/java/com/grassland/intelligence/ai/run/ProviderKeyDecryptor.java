package com.grassland.intelligence.ai.run;

import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 解析一次 provider 调用要用的密钥明文（同步）。任务书 #47 S2 起同时服务 BYOK 与平台凭据；
 * 2026-08-26 从 {@code AiExecutionService} 私有方法提炼，供执行环与用户态路由
 * {@link RoutedTextCompletionService} 共用——兜底语义只允许存在这一处。
 *
 * <p>三条路径（任务书 #58 决策 E：env qwen key bootstrap 兜底已删）：
 * <ul>
 * <li>有密文（BYOK 或平台凭据）→ 解密；无 KEK 抛 503（fail-closed，绝不退化）。</li>
 * <li>平台解析但凭据无密钥 → Sandbox 假 provider 返回 null（无需密钥）；其余一律 503
 *     「平台凭据缺失」——治理台为对应凭据配密钥后方可调用，不拿空 bearer 打上游。</li>
 * <li>其余（DENIED 等）→ null。</li>
 * </ul>
 *
 * <p>返回的明文只活在进程内调用链里，不入日志/响应/outbox。
 */
@Component
public class ProviderKeyDecryptor {

	private final ObjectProvider<EnvelopeEncryption> encryptionProvider;

	public ProviderKeyDecryptor(ObjectProvider<EnvelopeEncryption> encryptionProvider) {
		this.encryptionProvider = encryptionProvider;
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
		if ("sandbox".equalsIgnoreCase(provider.provider() == null ? "" : provider.provider())) {
			// 内置 Sandbox 假 provider（决策 F）：确定性本地实现，不需要密钥
			return null;
		}
		throw new IntelligenceException(503, "平台凭据缺失：该能力的凭据未配置密钥");
	}
}
