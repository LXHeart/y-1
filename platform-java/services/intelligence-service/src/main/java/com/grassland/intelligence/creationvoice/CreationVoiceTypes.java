package com.grassland.intelligence.creationvoice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.humanize.HumanizeInjectionService;
import com.grassland.intelligence.security.IntelligenceException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 私有文风档案的内部类型、DTO 与校验（任务书 #108 C-02 / W12；§6 接口契约、RULE-004～006）。
 *
 * <p>
 * 语义唯一来源：role 固定四值（RULE-004）；platform 固定
 * zhihu/xiaohongshu/dianping/moments、genre 固定
 * article/answer/note/short-post（RULE-005）；单档案 ≤30 条规则（每条 trim 后 1～300 UTF-16
 * code units）、≤5 份范文（正文 trim 后 1～2000）、consent 必须为 true、全 payload UTF-8
 * ≤64KiB、规则精确去重保序、 超限拒绝不截断（RULE-006）。unknown 字段拒绝；null 不等于缺省（§5.2）。
 *
 * <p>
 * 规范 JSON：payload（rules/samples）与 hash 输入（含 enabled）均为固定字段序的确定性序列化，
 * 相同目标内容必产生相同字符串与相同 SHA-256（幂等重放判定依赖这一点）。
 */
public final class CreationVoiceTypes {

	/** JSON 安全整数上限（2^53-1）：revision 为非负安全整数（§6.1）。 */
	private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

	public static final int MAX_RULES = 30;
	public static final int MAX_RULE_LENGTH = 300;
	public static final int MAX_SAMPLES = 5;
	public static final int MAX_SAMPLE_TEXT = 2000;
	public static final int MAX_PAYLOAD_BYTES = 64 * 1024;

	public static final Set<String> ROLES = Set.of("consumer", "merchant", "commercial-creator", "researcher");
	public static final Set<String> PLATFORMS = Set.of("zhihu", "xiaohongshu", "dianping", "moments");
	public static final Set<String> GENRES = Set.of("article", "answer", "note", "short-post");

	private static final ObjectMapper CANONICAL = new ObjectMapper();

	private CreationVoiceTypes() {
	}

	/** §6 Sample：id 为客户端 UUID（单档案内唯一），consent 只接受 true。 */
	public record VoiceSample(String id, String platform, String genre, String text, boolean consent) {
	}

	/** §6 Profile：缺行 GET 返回 revision=0、enabled=false、空数组、updatedAt=null（不是错误）。 */
	public record VoiceProfile(String role, long revision, boolean enabled, List<String> rules,
			List<VoiceSample> samples, String updatedAt) {

		public Map<String, Object> toMap() {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("role", role);
			map.put("revision", revision);
			map.put("enabled", enabled);
			map.put("rules", rules);
			map.put("samples", samples.stream().map(sample -> {
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("id", sample.id());
				item.put("platform", sample.platform());
				item.put("genre", sample.genre());
				item.put("text", sample.text());
				item.put("consent", sample.consent());
				return item;
			}).toList());
			map.put("updatedAt", updatedAt);
			return map;
		}

		public static VoiceProfile empty(String role) {
			return new VoiceProfile(role, 0L, false, List.of(), List.of(), null);
		}
	}

	public record PreviewRequest(String original, String edited, String platform, String genre) {
	}

	public static PreviewRequest parsePreviewRequest(Map<String, Object> body) {
		if (body == null || !body.keySet().equals(Set.of("original", "edited", "reason", "platform", "genre"))) {
			throw invalid("提炼请求必须且只能包含 original/edited/reason/platform/genre");
		}
		if (!"style".equals(body.get("reason")))
			throw invalid("仅长期表达习惯可提炼");
		for (String key : List.of("original", "edited")) {
			if (!(body.get(key) instanceof String value) || value.isBlank() || value.length() > 12000)
				throw invalid(key + " 长度必须为 1～12000");
		}
		if (!(body.get("platform") instanceof String platform) || !PLATFORMS.contains(platform)
				|| !(body.get("genre") instanceof String genre) || !GENRES.contains(genre))
			throw invalid("平台或体裁无效");
		return new PreviewRequest((String) body.get("original"), (String) body.get("edited"), platform, genre);
	}

	/** §6 API-002 PUT 请求：{expectedRevision,enabled,rules,samples} 全部必传。 */
	public record PutProfileRequest(long expectedRevision, boolean enabled, List<String> rules,
			List<VoiceSample> samples) {
	}

	/**
	 * brief.voice 判别联合（§6.6）的已解析形态： {@link Legacy}＝整体缺省（旧客户端兼容，图片链路仍走旧偏好附录）；
	 * {@link None}＝显式关闭（不查旧偏好、不读档案）； {@link Profile}＝按提交的 role+revision
	 * 精确读取（RULE-004/010）。
	 */
	public sealed interface VoiceSelection {
		record Legacy() implements VoiceSelection {
		}

		record None() implements VoiceSelection {
		}

		record Profile(String role, long revision) implements VoiceSelection {
		}
	}

	/** 四平台生成链解析结果（§6.6）：不可变；appendix 为组装好的注入段（空串=无内容）。 */
	public record ResolvedVoice(String mode, String role, long revision, String appendix, List<String> sampleHashes,
			String policyVersion) {

		public ResolvedVoice {
			appendix = appendix == null ? "" : appendix;
			sampleHashes = sampleHashes == null ? List.of() : List.copyOf(sampleHashes);
		}

		public static ResolvedVoice legacy() {
			return new ResolvedVoice("legacy", null, 0L, "", List.of(), HumanizeInjectionService.VOICE_POLICY_VERSION);
		}

		public static ResolvedVoice none() {
			return new ResolvedVoice("none", null, 0L, "", List.of(), HumanizeInjectionService.VOICE_POLICY_VERSION);
		}

		/** 日志速记（RULE-015：只含 mode/role/revision/样例数量与摘要，不含正文与 appendix）。 */
		public String logSummary() {
			return "mode=" + mode + " role=" + role + " revision=" + revision + " samples=" + sampleHashes.size()
					+ " policy=" + policyVersion;
		}
	}

	/**
	 * 从已校验 brief map 解析 voice 选择（§5.2：voice 出现时严格校验；null 不等同缺省必须拒绝）。 key 缺省 →
	 * {@code LEGACY}；结构/取值非法 → 400 INVALID_CREATION_BRIEF（brief 层错误码）。
	 */
	public static VoiceSelection voiceOf(Map<String, Object> brief) {
		if (brief == null || !brief.containsKey("voice")) {
			return new VoiceSelection.Legacy();
		}
		Object raw = brief.get("voice");
		if (!(raw instanceof Map<?, ?> voice)) {
			throw briefInvalid("voice 必须是对象（none 或 profile 判别联合），null 不等同缺省");
		}
		for (Object key : voice.keySet()) {
			if (!Set.of("mode", "role", "revision").contains(String.valueOf(key))) {
				throw briefInvalid("voice 不接受未知字段：" + key);
			}
		}
		Object mode = voice.get("mode");
		if (!(mode instanceof String modeText)) {
			throw briefInvalid("voice.mode 必须是 none 或 profile");
		}
		boolean hasRole = voice.containsKey("role");
		boolean hasRevision = voice.containsKey("revision");
		if ("none".equals(modeText)) {
			if (hasRole || hasRevision) {
				throw briefInvalid("voice=none 不能附带 role/revision");
			}
			return new VoiceSelection.None();
		}
		if (!"profile".equals(modeText)) {
			throw briefInvalid("voice.mode 必须是 none 或 profile");
		}
		if (!hasRole || !hasRevision) {
			throw briefInvalid("voice=profile 必须同时提供 role 与 revision");
		}
		Object role = voice.get("role");
		if (!(role instanceof String roleText) || !ROLES.contains(roleText)) {
			throw briefInvalid("voice.role 必须是 consumer/merchant/commercial-creator/researcher 之一");
		}
		Object revision = voice.get("revision");
		if (!(revision instanceof Number number) || revision instanceof Double || revision instanceof Float) {
			throw briefInvalid("voice.revision 必须是整数");
		}
		long value = toLong(number);
		if (value < 1 || value > MAX_SAFE_INTEGER) {
			throw briefInvalid("voice.revision 必须是不小于 1 的安全整数");
		}
		return new VoiceSelection.Profile(roleText, value);
	}

	/** brief 层 voice 错误（生成入口的既有错误码，§6.4）。 */
	static IntelligenceException briefInvalid(String detail) {
		return new IntelligenceException(400, "INVALID_CREATION_BRIEF", "创作简报 voice " + detail);
	}

	/** 409：profile 与 authorRole 不一致（§6.4；要求重新选择，不生成）。 */
	static IntelligenceException roleMismatch(String detail) {
		return new IntelligenceException(409, "VOICE_ROLE_MISMATCH", detail);
	}

	public static void requireValidRole(String role) {
		if (role == null || !ROLES.contains(role)) {
			throw invalid("role 必须是 consumer/merchant/commercial-creator/researcher 之一");
		}
	}

	/**
	 * 严格解析 PUT body：未知字段拒绝、null/缺失拒绝、类型与限额逐项校验（RULE-006）。 校验失败抛 400
	 * VOICE_INVALID_INPUT，不触碰模型与数据库。
	 */
	public static PutProfileRequest parsePutRequest(Map<String, Object> body) {
		if (body == null) {
			throw invalid("请求体不能为空");
		}
		for (String field : body.keySet()) {
			if (!Set.of("expectedRevision", "enabled", "rules", "samples").contains(field)) {
				throw invalid("未知字段被拒绝：" + field + "（不接受 accountId/organizationId 等账户参数）");
			}
		}
		if (writeCanonical(body).getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
			throw invalid("完整档案请求超过64KiB限制");
		}
		Object rawRevision = body.get("expectedRevision");
		if (rawRevision == null) {
			throw invalid("expectedRevision 必传");
		}
		if (!(rawRevision instanceof Number number) || rawRevision instanceof Double || rawRevision instanceof Float) {
			throw invalid("expectedRevision 必须是整数");
		}
		long expectedRevision = toLong(number);
		if (expectedRevision < 0 || expectedRevision > MAX_SAFE_INTEGER) {
			throw invalid("expectedRevision 必须是非负安全整数");
		}
		Object rawEnabled = body.get("enabled");
		if (!(rawEnabled instanceof Boolean enabled)) {
			throw invalid("enabled 必传且必须是布尔值");
		}
		List<String> rules = parseRules(body.get("rules"));
		List<VoiceSample> samples = parseSamples(body.get("samples"));
		if (enabled && rules.isEmpty() && samples.isEmpty()) {
			throw invalid("enabled=true 时至少要有一条规则或一份范文");
		}
		if (payloadJsonBytes(rules, samples) > MAX_PAYLOAD_BYTES) {
			throw invalid("rules/samples 总 payload 超过 64KiB 限制");
		}
		return new PutProfileRequest(expectedRevision, enabled, rules, samples);
	}

	private static List<String> parseRules(Object raw) {
		if (!(raw instanceof List<?> list)) {
			throw invalid("rules 必传且必须是字符串数组");
		}
		LinkedHashSet<String> deduped = new LinkedHashSet<>();
		for (Object item : list) {
			if (!(item instanceof String rule)) {
				throw invalid("rules 每条必须是字符串");
			}
			String trimmed = rule.strip();
			// RULE-006：每条 trim 后 1～300 UTF-16 code units（按 String.length() 度量）；空白规则拒绝。
			if (trimmed.isEmpty() || trimmed.length() > MAX_RULE_LENGTH) {
				throw invalid("rules 每条 trim 后长度必须在 1～300 之间");
			}
			deduped.add(trimmed);
		}
		List<String> rules = new ArrayList<>(deduped);
		if (rules.size() > MAX_RULES) {
			throw invalid("规则最多 " + MAX_RULES + " 条（精确去重后），超限拒绝不截断");
		}
		return List.copyOf(rules);
	}

	private static List<VoiceSample> parseSamples(Object raw) {
		if (!(raw instanceof List<?> list)) {
			throw invalid("samples 必传且必须是数组");
		}
		if (list.size() > MAX_SAMPLES) {
			throw invalid("范文最多 " + MAX_SAMPLES + " 份，超限拒绝不截断");
		}
		Set<String> seenIds = new LinkedHashSet<>();
		List<VoiceSample> samples = new ArrayList<>();
		for (Object item : list) {
			if (!(item instanceof Map<?, ?> map)) {
				throw invalid("samples 每份必须是对象");
			}
			Set<String> allowed = Set.of("id", "platform", "genre", "text", "consent");
			for (Object key : map.keySet()) {
				if (!allowed.contains(String.valueOf(key))) {
					throw invalid("samples 含未知字段：" + key);
				}
			}
			String id = stringField(map, "id");
			String platform = stringField(map, "platform");
			String genre = stringField(map, "genre");
			String text = stringField(map, "text");
			Object consent = map.get("consent");
			if (!Boolean.TRUE.equals(consent)) {
				throw invalid("samples.consent 必须为 true（本人作品/获准使用确认，不接受 null/false）");
			}
			try {
				String canonicalId = UUID.fromString(id).toString();
				if (!canonicalId.equalsIgnoreCase(id))
					throw new IllegalArgumentException();
				id = canonicalId;
			} catch (IllegalArgumentException e) {
				throw invalid("samples.id 必须是客户端生成的 UUID");
			}
			if (!PLATFORMS.contains(platform)) {
				throw invalid("samples.platform 必须是 zhihu/xiaohongshu/dianping/moments 之一");
			}
			if (!GENRES.contains(genre)) {
				throw invalid("samples.genre 必须是 article/answer/note/short-post 之一");
			}
			String trimmed = text.strip();
			if (trimmed.isEmpty() || trimmed.length() > MAX_SAMPLE_TEXT) {
				throw invalid("samples.text trim 后长度必须在 1～2000 之间");
			}
			if (!seenIds.add(id)) {
				throw invalid("samples.id 在单档案内必须唯一");
			}
			samples.add(new VoiceSample(id, platform, genre, trimmed, true));
		}
		return List.copyOf(samples);
	}

	private static String stringField(Map<?, ?> map, String field) {
		Object value = map.get(field);
		if (!(value instanceof String text) || text.isBlank()) {
			throw invalid("samples." + field + " 必传且必须是非空字符串");
		}
		return text;
	}

	private static long toLong(Number number) {
		if (number instanceof BigInteger big) {
			try {
				return big.longValueExact();
			} catch (ArithmeticException e) {
				return Long.MAX_VALUE;
			}
		}
		return number.longValue();
	}

	// ---------- 规范 JSON（确定性序列化；幂等 hash 依赖） ----------

	/** 加密 payload：rules/samples 的规范 JSON（固定字段序）。 */
	public static String payloadJson(List<String> rules, List<VoiceSample> samples) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("rules", rules);
		payload.put("samples", samples.stream().map(sample -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", sample.id());
			item.put("platform", sample.platform());
			item.put("genre", sample.genre());
			item.put("text", sample.text());
			item.put("consent", sample.consent());
			return item;
		}).toList());
		return writeCanonical(payload);
	}

	/** hash 输入：含 enabled 在内的规范目标内容（§7.1）。 */
	public static String canonicalContent(boolean enabled, List<String> rules, List<VoiceSample> samples) {
		Map<String, Object> content = new LinkedHashMap<>();
		content.put("enabled", enabled);
		content.put("rules", rules);
		content.put("samples", samples.stream().map(sample -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", sample.id());
			item.put("platform", sample.platform());
			item.put("genre", sample.genre());
			item.put("text", sample.text());
			item.put("consent", sample.consent());
			return item;
		}).toList());
		return writeCanonical(content);
	}

	public static int payloadJsonBytes(List<String> rules, List<VoiceSample> samples) {
		return payloadJson(rules, samples).getBytes(StandardCharsets.UTF_8).length;
	}

	/** 从存储 payload 规范 JSON 还原 rules/samples（损坏报 503，不当空档案）。 */
	public static StoredPayload parsePayload(String payloadJson) {
		try {
			Map<?, ?> parsed = CANONICAL.readValue(payloadJson, Map.class);
			Object rawRules = parsed.get("rules");
			Object rawSamples = parsed.get("samples");
			if (!(rawRules instanceof List<?> rulesList) || !(rawSamples instanceof List<?> samplesList)) {
				throw unavailable("档案 payload 结构损坏");
			}
			List<String> rules = new ArrayList<>();
			for (Object rule : rulesList) {
				if (!(rule instanceof String text)) {
					throw unavailable("档案 payload 规则损坏");
				}
				rules.add(text);
			}
			List<VoiceSample> samples = new ArrayList<>();
			for (Object sample : samplesList) {
				if (!(sample instanceof Map<?, ?> map) || !(map.get("id") instanceof String id)
						|| !(map.get("platform") instanceof String platform)
						|| !(map.get("genre") instanceof String genre) || !(map.get("text") instanceof String text)) {
					throw unavailable("档案 payload 范文损坏");
				}
				samples.add(new VoiceSample(id, platform, genre, text, Boolean.TRUE.equals(map.get("consent"))));
			}
			return new StoredPayload(List.copyOf(rules), List.copyOf(samples));
		} catch (IntelligenceException e) {
			throw e;
		} catch (Exception e) {
			throw unavailable("档案 payload 解析失败");
		}
	}

	/** 存储侧还原结果（rules/samples）。 */
	public record StoredPayload(List<String> rules, List<VoiceSample> samples) {
	}

	private static String writeCanonical(Object value) {
		try {
			return CANONICAL.writeValueAsString(value);
		} catch (Exception e) {
			throw unavailable("档案规范 JSON 序列化失败");
		}
	}

	static IntelligenceException invalid(String message) {
		return new IntelligenceException(400, "VOICE_INVALID_INPUT", message);
	}

	static IntelligenceException unavailable(String message) {
		return new IntelligenceException(503, "VOICE_UNAVAILABLE", message);
	}

	static IntelligenceException conflict(String message) {
		return new IntelligenceException(409, "VOICE_REVISION_CONFLICT", message);
	}

	/** 摘要工具（样例 hash / appendix 稳定性观测用；不落正文）。 */
	public static String sha256Hex(String content) {
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (Exception e) {
			throw unavailable("文风摘要计算失败");
		}
	}

	/** 账号生命周期屏障（§6.4：沿用既有屏障语义，frozen/erasing/erased 拒绝）。 */
	static IntelligenceException barrier(String state) {
		return new IntelligenceException(409, "account_closure_barrier", "账号生命周期状态为 " + state + "，私有文风档案不可用");
	}
}
