package com.grassland.intelligence.creationstyle;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 创作 style skill 两面端点（任务书 #57 决策 E/G）：
 *
 * <ul>
 * <li>用户侧目录 {@code GET /api/creation-style-skills?category=}?category}
 * 可省略=三组合并）—— 任意登录用户可读（纯目录无敏感内容），<b>绝不含 promptContent</b>（前端 chips 只需要
 * code/name/description/sortOrder）。治理台改完/停用即随下次拉取生效。</li>
 * <li>治理台 {@code GET/PUT /api/admin/creation-style-skills}——requireAdmin；GET
 * 全量含停用含 promptContent；PUT 整行提交（全必填包装类型），乐观锁 version 不符 → 409。</li>
 * </ul>
 */
@RestController
public class CreationStyleSkillController {

	private final IntelligenceCallerResolver callers;
	private final CreationStyleSkillRepository repository;

	public CreationStyleSkillController(IntelligenceCallerResolver callers, CreationStyleSkillRepository repository) {
		this.callers = callers;
		this.repository = repository;
	}

	// ---------- 用户侧目录 ----------

	@GetMapping("/api/creation-style-skills")
	public Mono<Map<String, Object>> list(@RequestParam(value = "category", required = false) String category,
			ServerWebExchange exchange) {
		CreationStyleSkillCategory parsed = CreationStyleSkillCategory.fromKey(category);
		if (category != null && !category.isBlank() && parsed == null) {
			throw new IntelligenceException(400, "未知的风格分类：" + category);
		}
		return callers.resolve(exchange.getRequest()).flatMap(caller -> repository.listEnabled(parsed).collectList())
				.map(skills -> Map.<String, Object>of("success", true, "data",
						Map.of("skills", skills.stream().map(CreationStyleSkillController::catalogItem).toList())));
	}

	/** 目录项：不含 promptContent（决策 E 红线）。 */
	private static Map<String, Object> catalogItem(CreationStyleSkill skill) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("category", skill.category().key());
		item.put("code", skill.code());
		item.put("name", skill.name());
		item.put("description", skill.description());
		item.put("sortOrder", skill.sortOrder());
		// 任务书 #62：平台归属下发前端做过滤（空数组=通用；治理台视角不在服务端过滤——决策见任务书卡2）
		item.put("applicablePlatforms", skill.applicablePlatforms());
		return item;
	}

	// ---------- 治理台 ----------

	@GetMapping("/api/admin/creation-style-skills")
	public Mono<Map<String, Object>> adminList(ServerWebExchange exchange) {
		return callers.requireAdmin(exchange.getRequest()).flatMap(admin -> repository.listAll().collectList())
				.map(skills -> Map.<String, Object>of("success", true, "data",
						Map.of("skills", skills.stream().map(CreationStyleSkillController::adminItem).toList())));
	}

	/** 治理台行：全字段含 promptContent + id/version/updatedAt（编辑弹窗整行回显）。 */
	private static Map<String, Object> adminItem(CreationStyleSkill skill) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("id", skill.id().toString());
		item.put("category", skill.category().key());
		item.put("code", skill.code());
		item.put("name", skill.name());
		item.put("description", skill.description());
		item.put("promptContent", skill.promptContent());
		item.put("enabled", skill.enabled());
		item.put("sortOrder", skill.sortOrder());
		item.put("applicablePlatforms", skill.applicablePlatforms());
		item.put("version", skill.version());
		item.put("updatedAt", skill.updatedAt() == null ? null : skill.updatedAt().toString());
		return item;
	}

	@PutMapping("/api/admin/creation-style-skills/{id}")
	public Mono<Map<String, Object>> adminUpdate(@PathVariable UUID id, @RequestBody UpdateRequest body,
			ServerWebExchange exchange) {
		if (body.name() == null || body.name().isBlank() || body.name().trim().length() > 30) {
			throw new IntelligenceException(400, "名称不能为空且不超过 30 字");
		}
		if (body.promptContent() == null || body.promptContent().isBlank()
				|| body.promptContent().trim().length() > 2000) {
			throw new IntelligenceException(400, "提示词不能为空且不超过 2000 字");
		}
		if (body.expectedVersion() == null) {
			throw new IntelligenceException(400, "缺少版本号（expectedVersion）");
		}
		if (body.enabled() == null) {
			throw new IntelligenceException(400, "缺少启用状态（enabled）");
		}
		List<String> platforms = normalizePlatforms(body.applicablePlatforms());
		return callers.requireAdmin(exchange.getRequest())
				.flatMap(admin -> repository.updateRow(id, body.name().trim(), body.description(),
						body.promptContent().trim(), body.enabled(), platforms, body.expectedVersion(),
						UUID.fromString(admin.accountId())))
				.map(skill -> Map.<String, Object>of("success", true, "data", Map.of("skill", adminItem(skill))))
				.switchIfEmpty(Mono.defer(() -> Mono.error(new IntelligenceException(409, "该风格已被他人修改，请刷新后重试"))));
	}

	/**
	 * 平台归属归一（任务书 #62）：trim + 去空 + 去重。<b>缺省（null）= 未提交，保持原归属</b>； 显式空数组 =
	 * 改为全平台通用——否则旧治理台前端一次编辑就会把知乎专属 skill 静默放开到全平台。 未知 platform id 显式 400——归属写错会让
	 * chip 在目标平台整组消失，静默接受等于埋坑。
	 */
	private static List<String> normalizePlatforms(List<String> raw) {
		if (raw == null) {
			return null; // 未提交 → 仓储层 COALESCE 保持原归属（区别于显式 [] = 改为通用）
		}
		List<String> out = new java.util.ArrayList<>();
		for (String item : raw) {
			if (item == null || item.isBlank()) {
				continue;
			}
			String trimmed = item.trim();
			if (!KNOWN_PLATFORMS.contains(trimmed)) {
				throw new IntelligenceException(400, "未知的适用平台：" + trimmed);
			}
			if (!out.contains(trimmed)) {
				out.add(trimmed);
			}
		}
		return List.copyOf(out);
	}

	/** 受控平台集（与前端 chips 过滤口径一致；空归属=通用不在此列）。 */
	private static final java.util.Set<String> KNOWN_PLATFORMS = java.util.Set.of("xiaohongshu", "zhihu", "douyin",
			"wechat");

	/**
	 * 整行提交（全必填包装类型——Jackson record 惯例，缺失即 400 由上面显式校验给出文案）。
	 * {@code applicablePlatforms} 可省略 = 保持原归属；显式 {@code []} = 改为通用。
	 */
	public record UpdateRequest(String name, String description, String promptContent, Boolean enabled,
			List<String> applicablePlatforms, Integer expectedVersion) {
	}
}
