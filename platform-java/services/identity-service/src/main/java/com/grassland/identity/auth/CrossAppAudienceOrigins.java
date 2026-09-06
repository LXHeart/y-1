package com.grassland.identity.auth;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 跨应用免登的目标应用（audience）枚举与核销 origin 门禁（任务书 #86）。
 *
 * <p>
 * audience 枚举只有 {@code grassland} 与 {@code ai}（小写、大小写敏感）——治理台有独立登录，
 * 无免登需求，不在列。每个 audience 可配置逗号分隔的受信 origin 列表（复用部署变量
 * {@code GRASSLAND_ORIGIN}/{@code AI_APP_ORIGIN}，与 nginx 注入前端 app-config 的同名同值，
 * 单一事实来源）；列表为空表示该 audience 跳过 origin 校验（本地 dev 同源形态）。
 *
 * <p>
 * 匹配是 scheme+host+port 的精确字符串相等（无通配、无后缀匹配）。Origin 头缺失放行
 * （部分浏览器同源 POST 不带 Origin；抗非浏览器重放的主体仍是 256 位随机 + GETDEL + TTL）。
 */
@Component
public class CrossAppAudienceOrigins {
	/** 合法目标应用枚举（D-02：写死，ops 不在列）。 */
	public static final List<String> AUDIENCES = List.of("grassland", "ai");

	private final String grasslandOrigins;
	private final String aiOrigins;

	public CrossAppAudienceOrigins(@Value("${identity.cross-app-token.audience-origins.grassland:}") String grasslandOrigins,
			@Value("${identity.cross-app-token.audience-origins.ai:}") String aiOrigins) {
		this.grasslandOrigins = grasslandOrigins;
		this.aiOrigins = aiOrigins;
	}

	/** audience 是否合法（null 安全：null → false）。调用方先 trim——组件本身不做 trim（D-03/D-07）。 */
	public static boolean validAudience(String audience) {
		return audience != null && AUDIENCES.contains(audience);
	}

	/** 该 audience 配置的受信 origin 列表（逗号分隔 → trim、去空）；未知 audience → 空列表。 */
	public List<String> origins(String audience) {
		String configured = "ai".equals(audience) ? aiOrigins
				: "grassland".equals(audience) ? grasslandOrigins
				: null;
		if (configured == null || configured.isBlank()) {
			return List.of();
		}
		return Arrays.stream(configured.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
	}

	/** origin 门禁：originHeader 空 → 放行；该 audience 未配置 origin 列表 → 放行；否则须精确包含。 */
	public boolean allows(String audience, String originHeader) {
		if (originHeader == null || originHeader.isBlank()) {
			return true;
		}
		List<String> allowed = origins(audience);
		if (allowed.isEmpty()) {
			return true;
		}
		return allowed.contains(originHeader);
	}

	/** 从 Origin 头推导来源应用（仅作签发载荷溯源，不参与校验）：命中 → 对应 audience；否则 unknown（含 null）。 */
	public String audienceOf(String originHeader) {
		if (originHeader == null || originHeader.isBlank()) {
			return "unknown";
		}
		for (String audience : AUDIENCES) {
			if (origins(audience).contains(originHeader)) {
				return audience;
			}
		}
		return "unknown";
	}
}
