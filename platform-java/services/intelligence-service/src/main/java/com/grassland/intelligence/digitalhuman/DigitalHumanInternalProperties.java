package com.grassland.intelligence.digitalhuman;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 数字人内部通道配置（任务书 #105D C105D-02 / 共享契约 K10、K13.2）。
 *
 * <p>
 * 默认 disabled：不启动 9143 mTLS 监听，主端口不挂任何 internal 映射。TLS 材料只引用部署注入的 secret
 * 文件路径（值不入库/不入仓）；SAN allowlist 固定 digital-human-runtime——只信握手证书，不信任何 转发的证书身份
 * header。端口与 TTL 有界校验，拒绝非法配置启动。
 */
@ConfigurationProperties(prefix = "digital-human.internal")
public record DigitalHumanInternalProperties(boolean enabled, int port, Tls tls, List<String> allowedAiOrigins,
		Duration grantTtl, Duration executionGrantTtl, Duration firstFrameTimeout) {

	public DigitalHumanInternalProperties {
		// port=0 允许（测试临时端口）；K13.2 生产默认 9143 由 yml 给出。
		if (port < 0 || port > 65535) {
			throw new IllegalArgumentException("digital-human.internal.port 必须在 0～65535");
		}
		Objects.requireNonNull(tls, "tls 必填（disabled 时可为空对象）");
		allowedAiOrigins = allowedAiOrigins == null ? List.of() : List.copyOf(allowedAiOrigins);
		grantTtl = grantTtl == null ? Duration.ofSeconds(30) : grantTtl;
		executionGrantTtl = executionGrantTtl == null ? Duration.ofSeconds(30) : executionGrantTtl;
		firstFrameTimeout = firstFrameTimeout == null ? Duration.ofSeconds(5) : firstFrameTimeout;
		if (grantTtl.isNegative() || grantTtl.toSeconds() > 60 || executionGrantTtl.isNegative()
				|| executionGrantTtl.toSeconds() > 60) {
			throw new IllegalArgumentException("grant TTL 必须在 0～60 秒（K07：票据 30 秒）");
		}
		if (enabled) {
			if (tls.certFile() == null || tls.keyFile() == null || tls.caFile() == null) {
				throw new IllegalArgumentException("digital-human.internal.enabled=true 需要 TLS 证书/私钥/CA 文件路径");
			}
			if (tls.clientSans() == null || tls.clientSans().isEmpty()) {
				throw new IllegalArgumentException("digital-human.internal.enabled=true 需要客户端 SAN allowlist");
			}
		}
	}

	/**
	 * K13.2
	 * 固定默认：DH_INTERNAL_PORT=9143、DH_INTERNAL_CLIENT_SAN=digital-human-runtime。
	 */
	public static DigitalHumanInternalProperties disabled() {
		return new DigitalHumanInternalProperties(false, 9143, new Tls(null, null, null, List.of()), List.of(),
				Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(5));
	}

	public record Tls(String certFile, String keyFile, String caFile, List<String> clientSans) {
		public Tls {
			clientSans = clientSans == null ? List.of() : List.copyOf(clientSans);
		}
	}
}
