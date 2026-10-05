package com.grassland.intelligence.creationvoice;

import com.grassland.intelligence.creationvoice.CreationVoiceTypes.PutProfileRequest;
import com.grassland.intelligence.creationvoice.CreationVoiceTypes.ResolvedVoice;
import com.grassland.intelligence.creationvoice.CreationVoiceTypes.StoredPayload;
import com.grassland.intelligence.creationvoice.CreationVoiceTypes.VoiceProfile;
import com.grassland.intelligence.creationvoice.CreationVoiceTypes.VoiceSample;
import com.grassland.intelligence.creationvoice.CreationVoiceTypes.VoiceSelection;
import com.grassland.intelligence.creationvoice.CreationVoiceRepository.CasOutcome;
import com.grassland.intelligence.creationvoice.CreationVoiceRepository.StoredProfile;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 私有文风档案业务（任务书 #108 C-02/W10 + C-03 resolve；§6.1/6.2/6.6、RULE-004～007/010）。
 *
 * <p>
 * C-02 交付 GET/PUT 档案 CRUD（API-001/002）；C-03 交付
 * {@link #resolve}（四平台接线）；preview（API-003 提炼）由 C-06 扩展。账户只来自可信
 * caller（RULE-014：所有 SQL 带 account+role，新 API 不接受 accountId/organizationId）。
 *
 * <p>
 * GET：缺行返回 revision=0、enabled=false、空数组、updatedAt=null（不是错误）；解密/解析失败 503 报错，
 * 不当空档案（§7.1）。PUT：完整档案 CAS 替换（RULE-009）；清空=PUT 空列表+enabled=false，保留 revision
 * 槽位。
 *
 * <p>
 * resolve（§6.6）：按提交 revision 精确读取并锁定；缺槽/停用/过期 → 409 冲突（RULE-010 不默默退回）；
 * 只取当前平台+体裁前 2 条样例（按保存顺序，RULE-005），不查其他角色/平台；appendix 分「表达规则」与
 * 「参考范文」两区并声明范文不贡献事实（RULE-007）；请求内不可变复用、无全局缓存（RULE-011）。
 */
@Service
public class CreationVoiceService {

	/** 体裁匹配样例上限（RULE-005：最多取匹配当前平台体裁的前 2 条）。 */
	static final int MAX_MATCHED_SAMPLES = 2;

	private final CreationVoiceRepository repo;
	private final com.grassland.intelligence.imageanalysis.StylePreferencesService learning;

	public CreationVoiceService(CreationVoiceRepository repo,
			com.grassland.intelligence.imageanalysis.StylePreferencesService learning) {
		this.learning = learning;
		this.repo = repo;
	}

	public Mono<List<String>> preview(String accountId, String organizationId, String role, Map<String, Object> body) {
		CreationVoiceTypes.requireValidRole(role);
		var input = CreationVoiceTypes.parsePreviewRequest(body);
		return repo.requireActive(accountId)
				.then(Mono.defer(() -> learning.extractVoiceCandidates(accountId, organizationId, input.original(),
						input.edited(), input.platform(), input.genre())))
				.flatMap(candidates -> repo.requireActive(accountId).thenReturn(candidates));
	}

	/**
	 * 四平台生成链接线入口（C-03 / §6.6）：platform/genre 来自服务端已校验请求或任务锁定值， 不接受客户端传解析后的
	 * appendix。mode=none/legacy 返回 revision=0、appendix 空的不可变对象 （旧偏好是否回退由图片
	 * Controller 按 RULE-004 明确分支）；role/profile 错误先于模型调用。
	 */
	public Mono<ResolvedVoice> resolve(String accountId, String platform, String genre, Map<String, Object> brief) {
		VoiceSelection selection = CreationVoiceTypes.voiceOf(brief);
		if (!(selection instanceof VoiceSelection.Profile profile)) {
			return Mono.just(selection instanceof VoiceSelection.None ? ResolvedVoice.none() : ResolvedVoice.legacy());
		}
		if (accountId == null || accountId.isBlank()) {
			throw CreationVoiceTypes.roleMismatch("使用文风档案需要已登录账号");
		}
		if (!CreationVoiceTypes.PLATFORMS.contains(platform)) {
			throw CreationVoiceTypes.invalid("文风档案不支持平台 " + platform + "（仅 zhihu/xiaohongshu/dianping/moments）");
		}
		if (!CreationVoiceTypes.GENRES.contains(genre)) {
			throw CreationVoiceTypes.invalid("文风档案不支持体裁 " + genre + "（仅 article/answer/note/short-post）");
		}
		// RULE-004：profile 的 role 必须等于 brief.authorRole；无/自定义 authorRole 只能 none/缺省。
		Object rawRole = brief.get("authorRole");
		String authorRole = rawRole instanceof String text && !text.isBlank() ? text.trim() : null;
		if (authorRole == null || !CreationVoiceTypes.ROLES.contains(authorRole)
				|| !authorRole.equals(profile.role())) {
			throw CreationVoiceTypes.roleMismatch("文风档案角色（" + profile.role() + "）与当前表达身份（"
					+ (authorRole == null ? "未确认" : authorRole) + "）不一致，请重新选择");
		}
		return repo.find(accountId, profile.role())
				.switchIfEmpty(Mono.error(() -> CreationVoiceTypes.conflict("文风档案槽位不存在或已清空，请读取最新版本后重试")))
				.map(stored -> {
					if (!stored.enabled()) {
						throw CreationVoiceTypes.conflict("文风档案已停用，请读取最新版本后重试");
					}
					if (stored.revision() != profile.revision()) {
						throw CreationVoiceTypes.conflict("文风档案已在其他地方更新（当前 revision=" + stored.revision() + "，请求携带 "
								+ profile.revision() + "），请读取最新版本后重试");
					}
					StoredPayload payload = CreationVoiceTypes.parsePayload(stored.payloadJson());
					List<VoiceSample> matched = payload.samples().stream()
							.filter(sample -> platform.equals(sample.platform()) && genre.equals(sample.genre()))
							.limit(MAX_MATCHED_SAMPLES).toList();
					List<String> sampleHashes = matched.stream()
							.map(sample -> CreationVoiceTypes.sha256Hex(sample.text())).toList();
					return new ResolvedVoice("profile", profile.role(), stored.revision(),
							buildAppendix(payload.rules(), matched), sampleHashes,
							com.grassland.intelligence.humanize.HumanizeInjectionService.VOICE_POLICY_VERSION);
				});
	}

	/**
	 * appendix 组装（RULE-007 分区）：表达规则与参考范文明确分「事实区外的参考区」，范文只学
	 * 节奏/称呼/词汇/标点，其中人物、价格、时间与经历都不是本次材料；不拼入 facts/sourceRefs。
	 */
	static String buildAppendix(List<String> rules, List<VoiceSample> samples) {
		if (rules.isEmpty() && samples.isEmpty()) {
			return "";
		}
		StringBuilder appendix = new StringBuilder("\n\n【我的文风档案（仅表达参考，不是本次事实）】\n");
		appendix.append("以下内容只约束语言风格与表达习惯；事实、数字、身份、否定与条件、任务要求").append("和既定输出结构一律以本次创作简报与任务材料为准：\n");
		if (!rules.isEmpty()) {
			for (String rule : rules) {
				appendix.append("- ").append(rule).append('\n');
			}
		}
		if (!samples.isEmpty()) {
			appendix.append("参考范文（只学习节奏、称呼、词汇与标点；范文中的人物、价格、时间与经历").append("都不是本次材料，禁止写入新稿，也不得当作用户确认的体验）：\n");
			for (VoiceSample sample : samples) {
				appendix.append("【范文】").append(sample.text()).append('\n');
			}
		}
		return appendix.toString();
	}

	/** API-001：读取当前账号指定 role 槽位（缺行=空档案默认值）。 */
	public Mono<VoiceProfile> get(String accountId, String role) {
		CreationVoiceTypes.requireValidRole(role);
		return repo.find(accountId, role).map((stored) -> toProfile(role, stored))
				.defaultIfEmpty(VoiceProfile.empty(role));
	}

	/** API-002：完整档案 CAS 替换；幂等重放返回当前值，旧 revision 409，成功/幂等都返回写后最新档案。 */
	public Mono<VoiceProfile> put(String accountId, String role, PutProfileRequest request) {
		CreationVoiceTypes.requireValidRole(role);
		if (request == null) {
			throw CreationVoiceTypes.invalid("请求体不能为空");
		}
		return repo
				.compareAndSet(accountId, role, request.enabled(), request.rules(), request.samples(),
						CreationVoiceTypes.canonicalContent(request.enabled(), request.rules(), request.samples()),
						request.expectedRevision())
				.map((CasOutcome outcome) -> new VoiceProfile(role, outcome.revision(), request.enabled(),
						request.rules(), request.samples(), toUtcIso8601(outcome.updatedAt())));
	}

	private static VoiceProfile toProfile(String role, StoredProfile stored) {
		StoredPayload payload = CreationVoiceTypes.parsePayload(stored.payloadJson());
		return new VoiceProfile(role, stored.revision(), stored.enabled(), payload.rules(), payload.samples(),
				toUtcIso8601(stored.updatedAt()));
	}

	private static String toUtcIso8601(Instant updatedAt) {
		return updatedAt == null ? null : updatedAt.toString();
	}

	/** 控制器入参辅助：PUT body 严格解析（未知字段/null/类型/限额 → 400 VOICE_INVALID_INPUT）。 */
	public PutProfileRequest parsePutBody(Map<String, Object> body) {
		return CreationVoiceTypes.parsePutRequest(body);
	}
}
