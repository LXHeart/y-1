package com.grassland.intelligence.hypit.build;

import com.grassland.intelligence.hypit.build.HypitPlanRepository.PlanRow;
import com.grassland.intelligence.hypit.build.HypitPlanRepository.PricingRow;
import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.execution.HypitExternalExecutionBridge;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.hypit.project.HypitProjectRepository;
import com.grassland.intelligence.hypit.project.HypitProjectRepository.ProjectRow;
import com.grassland.intelligence.hypit.security.HypitAccessService;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 计划/估价服务（任务书 #107-1 C107-08 / 卡步骤 3-8）。
 *
 * <p>check/plan/pricing 委托 sidecar 引擎命令（plan 在隔离 runner 内编译并评估
 * producer，宿主侧只做 Host 只读解析）；plan 按 planHash 内容寻址持久化（同内容
 * 幂等读原行，计划不可变，绝不 UPDATE）；估价快照 unknown 价格如实 null 不写 0
 * （K12.7）；build 预检比较 revision/profileHash/grant scope，变化 409 要求重新
 * plan（卡步骤 8）。
 */
@Service
public class HypitPlanService {

    /** plan/pricing 命令（编译+needs 评估+宿主解析）的引擎侧超时。 */
    private static final Duration PLAN_TIMEOUT = Duration.ofMinutes(6);

    /**
     * Profile hash 的过渡实现（07 未持久化 Profile，C23 落地部署绑定）：当前以
     * 「Profile 未配置」为稳定输入。C23 持久化后换成真实 Profile 内容 hash——换源
     * 前后 hash 空间不同，存量 plan 行以 revision 校验为准，如实写在卡内。
     */
    private static final String PROFILE_HASH_TRANSITIONAL =
            HypitExternalExecutionBridge.sha256Hex("{\"profile\":null}");

    private final HypitProperties properties;
    private final HypitSidecarClient sidecar;
    private final HypitProjectRepository projects;
    private final HypitPlanRepository plans;

    public HypitPlanService(HypitProperties properties, HypitSidecarClient sidecar,
            HypitProjectRepository projects, HypitPlanRepository plans) {
        this.properties = properties;
        this.sidecar = sidecar;
        this.projects = projects;
        this.plans = plans;
    }

    public record CheckView(boolean ok, String sourceKind, String frontend,
            List<Map<String, Object>> diagnostics, List<String> modules, String sourceClosureHash,
            List<String> targetNames) {
    }

    /** 卡步骤 3：check 只读编译；不启动模型、不下载程序、不改 Profile。 */
    public Mono<CheckView> check(String accountId, UUID projectId, String entryFile) {
        return requireProject(accountId, projectId)
                .flatMap(project -> command("plan-check-" + UUID.randomUUID(), "check",
                        Map.of("projectId", project.id().toString(), "entryFile", entryFile)))
                .map(result -> {
                    Map<String, Object> view = HypitJson.mapValue(result);
                    return new CheckView(
                            Boolean.TRUE.equals(view.get("ok")),
                            HypitJson.stringValue(view.get("sourceKind"), "author"),
                            HypitJson.stringValue(view.get("frontend"), ""),
                            mapList(view.get("diagnostics")),
                            stringList(view.get("modules")),
                            HypitJson.stringValue(view.get("sourceClosureHash"), ""),
                            stringList(view.get("targetNames")));
                });
    }

    public record PlanView(PlanRow row, boolean ok, List<String> missingCapabilities,
            List<String> unresolvedRequests, List<String> unsupportedRequests,
            List<Map<String, Object>> providers, List<Map<String, Object>> reuse) {
    }

    /**
     * 卡步骤 4/6：完整 Run 图计划；revision 冻结为计划时工程当前 revision（步骤 8
     * 预检基准）；planHash 内容寻址，同内容幂等返回原行。
     */
    public Mono<PlanView> plan(String accountId, UUID projectId, String runFile) {
        return requireProject(accountId, projectId)
                .flatMap(project -> command("plan-build-" + UUID.randomUUID(), "plan",
                        Map.of("projectId", project.id().toString(), "runFile", runFile))
                        .flatMap(result -> {
                            Map<String, Object> doc = HypitJson.mapValue(result);
                            String planHash = HypitJson.stringValue(doc.get("planHash"), "");
                            if (planHash.isBlank()) {
                                return Mono.error(new IntelligenceException(HttpStatus.BAD_GATEWAY.value(),
                                        "hypit_engine_error", "plan 命令未返回 planHash"));
                            }
                            return plans.insertPlan(UUID.randomUUID(), projectId, project.revision(),
                                    runFile, planHash, PROFILE_HASH_TRANSITIONAL, HypitJson.write(doc))
                                    .map(row -> new PlanView(row,
                                            Boolean.TRUE.equals(doc.get("ok")),
                                            stringList(doc.get("missingCapabilities")),
                                            stringList(doc.get("unresolvedRequests")),
                                            stringList(doc.get("unsupportedRequests")),
                                            mapList(doc.get("providers")),
                                            mapList(doc.get("reuse"))));
                        }));
    }

    public record PricingView(PricingRow snapshot, List<Map<String, Object>> rows, int knownCosts,
            List<String> unknownRequests) {
    }

    /** 计划历史（不可变行，新→旧）。 */
    public reactor.core.publisher.Flux<PlanRow> listPlansAsFlux(UUID projectId, int limit) {
        return plans.listPlans(projectId, limit);
    }

    /** 卡步骤 7：只读该 plan 精确 requests 的原 provider 报价；unknown=null；不 submit。 */
    public Mono<PricingView> pricing(String accountId, UUID projectId, UUID planId) {
        return requireProject(accountId, projectId)
                .flatMap(project -> plans.findPlanById(planId)
                        .switchIfEmpty(Mono.error(HypitAccessService.notFound()))
                        .flatMap(plan -> {
                            if (!plan.projectId().equals(projectId)) {
                                return Mono.error(HypitAccessService.notFound());
                            }
                            return command("plan-pricing-" + UUID.randomUUID(), "pricing",
                                    Map.of("projectId", project.id().toString(),
                                            "runFile", plan.runFile()))
                                    .flatMap(result -> {
                                        Map<String, Object> doc = HypitJson.mapValue(result);
                                        List<Map<String, Object>> rows = mapList(doc.get("rows"));
                                        String pricingHash = HypitJson.stringValue(doc.get("pricingHash"), "");
                                        List<String> unknowns = stringList(doc.get("unknownRequests"));
                                        return plans.insertPricing(UUID.randomUUID(), planId, pricingHash,
                                                HypitJson.write(Map.of("rows", rows)),
                                                unknowns.isEmpty() ? null
                                                        : HypitJson.write(Map.of("requests", unknowns)))
                                                .map(snapshot -> new PricingView(snapshot, rows,
                                                        (int) HypitJson.longValue(doc.get("knownCosts"), 0),
                                                        unknowns));
                                    });
                        }));
    }

    /**
     * 卡步骤 8：build 预检——计划必须仍与工程事实一致；变化 409 要求重新 plan；
     * 禁止归档期重新 plan 冒充历史估价。
     */
    public Mono<PlanRow> requirePlanFresh(ProjectRow project, UUID planId) {
        return plans.findPlanById(planId)
                .switchIfEmpty(Mono.error(HypitAccessService.notFound()))
                .flatMap(plan -> {
                    if (!plan.projectId().equals(project.id())) {
                        return Mono.error(HypitAccessService.notFound());
                    }
                    if (plan.revision() != project.revision()) {
                        return Mono.error(new IntelligenceException(HttpStatus.CONFLICT.value(),
                                "hypit_plan_stale", "工程 revision 已前进（计划 " + plan.revision()
                                        + "，当前 " + project.revision() + "），重新 plan"));
                    }
                    if (!plan.profileHash().equals(PROFILE_HASH_TRANSITIONAL)) {
                        return Mono.error(new IntelligenceException(HttpStatus.CONFLICT.value(),
                                "hypit_plan_stale", "运行 Profile 已变化，重新 plan"));
                    }
                    return Mono.just(plan);
                });
    }

    /** grant scope 对计划的包含校验（scope targets 必须都落在计划 targets 内）。 */
    public static void requireGrantCovers(PlanRow plan, String grantScopeJson) {
        Map<String, Object> scope = HypitJson.read(grantScopeJson);
        List<String> targets = stringList(scope.get("targets"));
        if (targets.isEmpty()) {
            return;
        }
        Map<String, Object> planDoc = HypitJson.read(plan.planJson());
        List<String> planTargets = stringList(planDoc.get("targets"));
        for (String target : targets) {
            if (!planTargets.contains(target)) {
                throw new IntelligenceException(HttpStatus.CONFLICT.value(), "hypit_plan_stale",
                        "授权 scope 的 target 不在计划内：" + target);
            }
        }
    }

    private Mono<ProjectRow> requireProject(String accountId, UUID projectId) {
        return projects.findOwned(accountId, projectId)
                .filter(project -> !"deleted".equals(project.status()))
                .switchIfEmpty(Mono.error(HypitAccessService.notFound()));
    }

    private Mono<Map<String, Object>> command(String commandId, String kind, Map<String, Object> payload) {
        if (!properties.enabled()) {
            return Mono.error(HypitAccessService.disabled());
        }
        return sidecar.commandAsync(commandId, kind, payload)
                .timeout(PLAN_TIMEOUT)
                .map(command -> {
                    if (command.result() == null) {
                        throw new IntelligenceException(HttpStatus.BAD_GATEWAY.value(), "hypit_engine_error",
                                "命令 " + kind + " 失败：" + describe(command.error()));
                    }
                    return HypitJson.mapValue(command.result());
                });
    }

    private static String describe(Map<String, Object> error) {
        if (error == null) {
            return "无诊断";
        }
        return HypitJson.stringValue(error.get("code"), "engine") + ": "
                + HypitJson.stringValue(error.get("message"), "(无错误信息)");
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) map;
                out.add(casted);
            }
        }
        return out;
    }
}
