package com.grassland.intelligence.creationstudio.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** 计划包共用序列化／摘要工具（与 creationstudio 包同款口径，避免跨包私有静态可见性问题）。 */
final class PlanJson {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private PlanJson() {
	}

	static String json(Object value) {
		try {
			return MAPPER.writeValueAsString(value);
		} catch (Exception error) {
			throw new IllegalStateException("计划序列化失败", error);
		}
	}

	static Map<String, Object> readJson(String json) {
		if (json == null || json.isBlank() || "{}".equals(json)) {
			return Map.of();
		}
		try {
			return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
			});
		} catch (Exception error) {
			return Map.of();
		}
	}

	static String sha256(String text) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (Exception error) {
			throw new IllegalStateException("摘要计算失败", error);
		}
	}
}
