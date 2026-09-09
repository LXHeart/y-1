package com.grassland.intelligence.creationassistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.creationcontext.CreationBriefInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作区负载（任务书 #92 C-02 / D-04）：保存步骤、表单文本、来源 ID、runState 等
 * 「可恢复数据」；不保存密钥、Cookie、data URL、签名 URL 或完整二进制。
 *
 * <p>
 * 服务端只做结构闸——对象形态、64KB 上限、敏感键拒绝（含嵌套）、capability/runState 枚举、 结果/运行 ID
 * 列表去重与上限。{@code currentStep} 白名单是前端域能力域规则（未知值归一为首步）， 服务端不重复维护，只约束其为字符串。
 *
 * <p>
 * Jackson 惯例与 {@code StylePreferencesRepository} 一致：service-local mapper，不注入容器
 * bean。
 */
public final class CreationWorkspace {

	/** 序列化后整个 workspace ≤64KB（§5.1 inputs 行的上限口径）。 */
	static final int MAX_SERIALIZED_BYTES = 64 * 1024;

	/** §5.2.3 禁止字段：出现即 400 且不落库。 */
	private static final Set<String> FORBIDDEN_KEYS = Set.of("secret", "cookie", "dataUrl", "signedUrl");

	/** §5.1 capability 白名单（与前端 CREATION_CAPABILITIES 对齐）。 */
	static final Set<String> CAPABILITIES = Set.of("article", "image", "video", "moments");

	/** §4.3 runState 值集（存于 workspace_json，不扩展既有 status 枚举）。 */
	static final Set<String> RUN_STATES = Set.of("idle", "running", "succeeded", "failed");
	private static final Set<String> RESULT_REF_TYPES = Set.of("media", "content-asset");

	static final int MAX_ID_LIST_SIZE = 20;

	static final int MAX_ID_LENGTH = 64;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final Map<String, Object> value;

	private CreationWorkspace(Map<String, Object> value) {
		this.value = value;
	}

	public static CreationWorkspace empty() {
		return new CreationWorkspace(new LinkedHashMap<>());
	}

	public Map<String, Object> value() {
		return value;
	}

	/** capability 真相源在 workspace_json 内（§7.2）；无则 null（读侧由响应层回填 article 默认）。 */
	public String capability() {
		Object raw = value.get("capability");
		return raw instanceof String text ? text : null;
	}

	/** 序列化为落库 JSON（写侧保证过校验，理论不抛）。 */
	public String toJson() {
		try {
			return MAPPER.writeValueAsString(value);
		} catch (Exception e) {
			return "{}";
		}
	}

	/**
	 * 校验并归一化工作区（写路径唯一入口）。{@code capability} 为请求顶层字段：非空时校验枚举并注入 workspace；与
	 * workspace 内既有 capability 冲突 → 400。
	 */
	public static CreationWorkspace parse(Map<String, Object> raw, String capability) {
		Map<String, Object> normalized = new LinkedHashMap<>();
		if (raw != null) {
			normalized.putAll(raw);
		}
		rejectForbiddenKeys(normalized, "workspace");
		requireWritable(normalized);
		Object inlineCapability = normalized.get("capability");
		if (inlineCapability != null && !(inlineCapability instanceof String)) {
			throw invalid("workspace.capability 必须是字符串");
		}
		if (capability != null) {
			if (!CAPABILITIES.contains(capability)) {
				throw invalid("capability 无效");
			}
			if (inlineCapability != null && !capability.equals(inlineCapability)) {
				throw invalid("capability 与 workspace.capability 不一致");
			}
			normalized.put("capability", capability);
		} else if (inlineCapability != null && !CAPABILITIES.contains((String) inlineCapability)) {
			throw invalid("workspace.capability 无效");
		}
		Object runState = normalized.get("runState");
		if (runState != null) {
			if (!(runState instanceof String state) || !RUN_STATES.contains(state)) {
				throw invalid("workspace.runState 无效");
			}
		}
		requireString(normalized.get("currentStep"), "workspace.currentStep");
		requireString(normalized.get("sourceLabel"), "workspace.sourceLabel");
		Object inputs = normalized.get("inputs");
		if (inputs != null && !(inputs instanceof Map)) {
			throw invalid("workspace.inputs 必须是对象");
		}
		if (inputs instanceof Map<?, ?> fields)
			CreationBriefInput.validate(fields.get("brief"));
		CreationBriefInput.validate(normalized.get("brief"));
		validateResultRefs(normalized.get("resultRefs"));
		validateDeclarations(normalized.get("delivery"));
		try {
			byte[] serialized = MAPPER.writeValueAsBytes(normalized);
			if (serialized.length > MAX_SERIALIZED_BYTES) {
				throw invalid("workspace 超过 64KB 上限");
			}
		} catch (IntelligenceException e) {
			throw e;
		} catch (Exception e) {
			throw invalid("workspace 无法序列化");
		}
		return new CreationWorkspace(normalized);
	}

	public static void requireWritable(Map<String, Object> workspace) {
		Object schema = workspace.get("schemaVersion");
		if (schema != null && (!(schema instanceof Number version) || version.doubleValue() != 1)) {
			throw new IntelligenceException(400, "UNSUPPORTED_WORKSPACE_SCHEMA", "当前工作区版本暂不支持编辑");
		}
	}

	private static void validateDeclarations(Object raw) {
		if (raw == null)
			return;
		if (!(raw instanceof Map<?, ?> delivery))
			throw invalid("workspace.delivery 必须是对象");
		Object rawDeclarations = delivery.get("declarations");
		if (rawDeclarations == null)
			return;
		if (!(rawDeclarations instanceof Map<?, ?> declarations))
			throw invalid("内容声明必须是对象");
		for (String key : List.of("aiGenerated", "commercial", "original")) {
			Object state = declarations.get(key);
			if (state != null && (!(state instanceof String text)
					|| !Set.of("pending", "confirmed", "not-applicable").contains(text))) {
				throw invalid("内容声明 " + key + " 状态无效");
			}
		}
	}

	private static void validateResultRefs(Object raw) {
		if (raw == null)
			return;
		if (!(raw instanceof List<?> list) || list.size() > MAX_ID_LIST_SIZE) {
			throw invalid("workspace.resultRefs 最多 " + MAX_ID_LIST_SIZE + " 个");
		}
		Set<String> seen = new LinkedHashSet<>();
		for (Object item : list) {
			if (!(item instanceof Map<?, ?> ref))
				throw invalid("workspace.resultRefs 元素必须是对象");
			Object id = ref.get("id");
			if (!(id instanceof String text) || text.isBlank() || text.trim().length() > MAX_ID_LENGTH) {
				throw invalid("workspace.resultRefs.id 无效");
			}
			Object type = ref.get("refType");
			if (!(type instanceof String value) || !RESULT_REF_TYPES.contains(value)) {
				throw invalid("workspace.resultRefs.refType 无效");
			}
			if (!seen.add(type + ":" + id))
				throw invalid("workspace.resultRefs 不允许重复引用");
			Object position = ref.get("position");
			if (position != null
					&& (!(position instanceof Number n) || n.intValue() < 1 || n.doubleValue() != n.intValue())) {
				throw invalid("workspace.resultRefs.position 必须是正整数");
			}
			for (String field : List.of("role", "cardId", "runId", "storyboardId", "productionTaskId", "taskId")) {
				checkTextLength(ref.get(field), MAX_ID_LENGTH, "workspace.resultRefs." + field);
			}
		}
	}

	private static void checkTextLength(Object raw, int maxLength, String field) {
		if (raw != null && (!(raw instanceof String text) || text.trim().length() > maxLength)) {
			throw invalid(field + " 过长或类型无效");
		}
	}

	/** 读侧防御（§6.6）：列表返回前剥离敏感键——写侧已拒，防的是历史脏数据外泄。 */
	public static Map<String, Object> sanitizeForRead(Map<String, Object> workspace) {
		if (workspace == null || workspace.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> copy = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : workspace.entrySet()) {
			if (FORBIDDEN_KEYS.contains(entry.getKey())) {
				continue;
			}
			copy.put(entry.getKey(), sanitizeValue(entry.getValue()));
		}
		return copy;
	}

	/** 结果资产/运行 ID 列表归一（§5.1）：元素字符串化、trim、去重保序、≤20 个、单项 ≤64 字符。 */
	public static List<String> normalizeIdList(List<String> raw, String field) {
		if (raw == null || raw.isEmpty()) {
			return List.of();
		}
		Set<String> seen = new LinkedHashSet<>();
		for (String item : raw) {
			if (item == null) {
				throw invalid(field + " 元素必须是字符串");
			}
			String trimmed = item.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			if (trimmed.length() > MAX_ID_LENGTH) {
				throw invalid(field + " 单项过长");
			}
			seen.add(trimmed);
		}
		if (seen.size() > MAX_ID_LIST_SIZE) {
			throw invalid(field + " 最多 " + MAX_ID_LIST_SIZE + " 个");
		}
		return List.copyOf(seen);
	}

	private static Object sanitizeValue(Object node) {
		if (node instanceof Map<?, ?> map) {
			Map<String, Object> copy = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				String key = String.valueOf(entry.getKey());
				if (FORBIDDEN_KEYS.contains(key)) {
					continue;
				}
				copy.put(key, sanitizeValue(entry.getValue()));
			}
			return copy;
		}
		if (node instanceof List<?> list) {
			List<Object> copy = new ArrayList<>();
			for (Object item : list) {
				copy.add(sanitizeValue(item));
			}
			return copy;
		}
		return node;
	}

	private static void rejectForbiddenKeys(Map<String, Object> node, String path) {
		for (Map.Entry<String, Object> entry : node.entrySet()) {
			if (FORBIDDEN_KEYS.contains(entry.getKey())) {
				throw invalid(path + " 含禁止字段 " + entry.getKey());
			}
			if (entry.getValue() instanceof Map<?, ?> nested) {
				@SuppressWarnings("unchecked")
				Map<String, Object> nestedMap = (Map<String, Object>) nested;
				rejectForbiddenKeys(nestedMap, path + "." + entry.getKey());
			} else if (entry.getValue() instanceof List<?> list) {
				for (Object item : list) {
					if (item instanceof Map<?, ?> nestedInList) {
						@SuppressWarnings("unchecked")
						Map<String, Object> nestedListMap = (Map<String, Object>) nestedInList;
						rejectForbiddenKeys(nestedListMap, path + "." + entry.getKey() + "[]");
					}
				}
			}
		}
	}

	private static void requireString(Object value, String field) {
		if (value != null && !(value instanceof String)) {
			throw invalid(field + " 必须是字符串");
		}
	}

	static IntelligenceException invalid(String message) {
		return new IntelligenceException(400, "INVALID_WORKSPACE", message);
	}
}
