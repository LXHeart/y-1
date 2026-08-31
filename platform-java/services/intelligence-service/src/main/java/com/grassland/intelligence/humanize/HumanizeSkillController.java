package com.grassland.intelligence.humanize;

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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 去AI味 skill 治理台端点（任务书 #61）：
 *
 * <ul>
 * <li>{@code GET /api/admin/humanize-skills}——全量行（含 promptContent）+
 * 当前激活项与配置版本。</li>
 * <li>{@code PUT /api/admin/humanize-skills/{id}}——整行编辑（乐观锁 409）。</li>
 * <li>{@code PUT /api/admin/humanize-skills/active}——切换单选激活（null=关闭注入；乐观锁
 * 409）。</li>
 * </ul>
 */
@RestController
public class HumanizeSkillController {

	private final IntelligenceCallerResolver callers;
	private final HumanizeSkillRepository skills;
	private final HumanizeConfigRepository config;

	public HumanizeSkillController(IntelligenceCallerResolver callers, HumanizeSkillRepository skills,
			HumanizeConfigRepository config) {
		this.callers = callers;
		this.skills = skills;
		this.config = config;
	}

	@GetMapping("/api/admin/humanize-skills")
	public Mono<Map<String, Object>> adminList(ServerWebExchange exchange) {
		return callers.requireAdmin(exchange.getRequest())
				.flatMap(admin -> Mono.zip(skills.listAll().collectList(), config.findOrDefault()))
				.map(tuple -> Map.<String, Object>of("success", true, "data",
						Map.of("skills", tuple.getT1().stream().map(HumanizeSkillController::adminItem).toList(),
								"activeSkillCode",
								tuple.getT2().activeSkillCode() == null ? "" : tuple.getT2().activeSkillCode(),
								"configVersion", tuple.getT2().version())));
	}

	/** activeSkillCode 用空串表示 null（Map.of 不允许 null 值；前端约定空串=未激活）。 */
	private static Map<String, Object> adminItem(HumanizeSkill skill) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("id", skill.id().toString());
		item.put("code", skill.code());
		item.put("displayName", skill.displayName());
		item.put("description", skill.description());
		item.put("promptContent", skill.promptContent());
		item.put("sourceRepo", skill.sourceRepo());
		item.put("sourceLicense", skill.sourceLicense());
		item.put("enabled", skill.enabled());
		item.put("version", skill.version());
		item.put("updatedAt", skill.updatedAt() == null ? null : skill.updatedAt().toString());
		return item;
	}

	@PutMapping("/api/admin/humanize-skills/{id}")
	public Mono<Map<String, Object>> adminUpdate(@PathVariable UUID id, @RequestBody UpdateRequest body,
			ServerWebExchange exchange) {
		if (body.displayName() == null || body.displayName().isBlank() || body.displayName().trim().length() > 30) {
			throw new IntelligenceException(400, "名称不能为空且不超过 30 字");
		}
		if (body.promptContent() == null || body.promptContent().isBlank()
				|| body.promptContent().trim().length() > 3000) {
			throw new IntelligenceException(400, "规则内容不能为空且不超过 3000 字");
		}
		if (body.expectedVersion() == null) {
			throw new IntelligenceException(400, "缺少版本号（expectedVersion）");
		}
		if (body.enabled() == null) {
			throw new IntelligenceException(400, "缺少启用状态（enabled）");
		}
		return callers.requireAdmin(exchange.getRequest())
				.flatMap(admin -> skills.updateRow(id, body.displayName().trim(), body.description(),
						body.promptContent().trim(), body.enabled(), body.expectedVersion(),
						UUID.fromString(admin.accountId())))
				.map(skill -> Map.<String, Object>of("success", true, "data", Map.of("skill", adminItem(skill))))
				.switchIfEmpty(Mono.defer(() -> Mono.error(new IntelligenceException(409, "该规则已被他人修改，请刷新后重试"))));
	}

	/** 整行提交（可选字段一律包装类型——Jackson record 惯例，缺失即 400）。 */
	public record UpdateRequest(String displayName, String description, String promptContent, Boolean enabled,
			Integer expectedVersion) {
	}

	@PutMapping("/api/admin/humanize-skills/active")
	public Mono<Map<String, Object>> adminActivate(@RequestBody ActivateRequest body, ServerWebExchange exchange) {
		if (body.expectedConfigVersion() == null) {
			throw new IntelligenceException(400, "缺少配置版本号（expectedConfigVersion）");
		}
		String code = body.activeSkillCode() == null || body.activeSkillCode().isBlank()
				? null
				: body.activeSkillCode().trim();
		return callers.requireAdmin(exchange.getRequest())
				.flatMap(admin -> activate(admin.accountId(), code, body.expectedConfigVersion()))
				.map(configRow -> Map.<String, Object>of("success", true, "data",
						Map.of("activeSkillCode",
								configRow.activeSkillCode() == null ? "" : configRow.activeSkillCode(), "configVersion",
								configRow.version())));
	}

	private Mono<HumanizeConfigRepository.HumanizeConfig> activate(String adminId, String code, long expectedVersion) {
		if (code == null) {
			return conflictIfEmpty(config.upsertActive(null, expectedVersion, adminId));
		}
		return skills.findByCode(code)
				.switchIfEmpty(Mono.defer(() -> Mono.error(new IntelligenceException(400, "未知的规则 code: " + code))))
				.flatMap(skill -> skill.enabled()
						? conflictIfEmpty(config.upsertActive(code, expectedVersion, adminId))
						: Mono.error(new IntelligenceException(400, "该规则已停用，请先启用再激活")));
	}

	/**
	 * 乐观锁写空 Mono → 409（{@code upsertActive} 两个分支的冲突语义，见其 javadoc「上层转 409」）。 未知 code
	 * 的 400 判定必须挂在 {@code findByCode} 上而非整链尾部——否则版本冲突的空 Mono 会被误报成「未知的规则 code」。
	 */
	private static Mono<HumanizeConfigRepository.HumanizeConfig> conflictIfEmpty(
			Mono<HumanizeConfigRepository.HumanizeConfig> write) {
		return write.switchIfEmpty(Mono.defer(() -> Mono.error(new IntelligenceException(409, "激活配置已被他人修改，请刷新后重试"))));
	}

	/** activeSkillCode 传 null 或空串 = 关闭注入。 */
	public record ActivateRequest(String activeSkillCode, Long expectedConfigVersion) {
	}
}
