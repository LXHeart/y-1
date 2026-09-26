package com.grassland.intelligence.hypit.project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * Hypit 域内 JSON 工具（服务本地 ObjectMapper 实例——intelligence 无全局 bean，注入会拖垮 整个 Spring
 * 上下文；本类只做 Map 读写，不承担业务校验）。
 */
public final class HypitJson {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private HypitJson() {
	}

	public static Map<String, Object> read(String json) {
		try {
			return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
			});
		} catch (Exception error) {
			throw new IllegalArgumentException("hypit json decode failed", error);
		}
	}

	public static String write(Object value) {
		try {
			return MAPPER.writeValueAsString(value);
		} catch (Exception error) {
			throw new IllegalArgumentException("hypit json encode failed", error);
		}
	}

	public static long longValue(Object value, long fallback) {
		return value instanceof Number number ? number.longValue() : fallback;
	}

	public static String stringValue(Object value, String fallback) {
		return value instanceof String string ? string : fallback;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> mapValue(Object value) {
		return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
	}
}
