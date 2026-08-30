package com.grassland.intelligence.creationstyle;

import com.grassland.intelligence.security.IntelligenceException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 生成流校验门（任务书 #57 决策 F）：code 直读库、无缓存——admin 改完/停用后下一次生成立即生效。
 *
 * <p>code 未知或停用 → 400「所选{标题套路|体裁|文风}无效或已停用，请重新选择」；
 * 该失败必须发生在任何上游调用与扣费之前（Controller 在执行环之前先 resolve）。
 */
@Service
public class CreationStyleSkillService {

    private final CreationStyleSkillRepository repository;

    public CreationStyleSkillService(CreationStyleSkillRepository repository) {
        this.repository = repository;
    }

    /** 解析启用中的 skill；空 code 跳过（Mono.empty = 不注入=现状）。 */
    public Mono<CreationStyleSkill> requireEnabled(CreationStyleSkillCategory category, String code) {
        if (code == null || code.isBlank()) {
            return Mono.empty();
        }
        String trimmed = code.trim();
        return repository.findByCode(category, trimmed)
                .flatMap(skill -> skill.enabled()
                        ? Mono.just(skill)
                        : Mono.error(invalid(category)))
                .switchIfEmpty(Mono.defer(() -> Mono.error(invalid(category))));
    }

    private static IntelligenceException invalid(CreationStyleSkillCategory category) {
        return new IntelligenceException(400, "所选" + category.label() + "无效或已停用，请重新选择");
    }
}
