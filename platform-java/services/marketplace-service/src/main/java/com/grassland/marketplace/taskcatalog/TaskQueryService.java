package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.analytics.AnalyticsRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Component;

/**
 * Authorized task details, organization lists, preview, usage and analytics.
 */
@Component
public class TaskQueryService {
	private final TaskRepository tasks;
	private final TaskResourceAuthorization taskAuthorization;
	private final TaskMetricsRepository metrics;
	private final AnalyticsRepository analytics;
	private final TaskStoreEnrichment storeEnrichment;
	private final TaskPreviewService previewService;
	private final com.grassland.marketplace.reputation.MerchantCreditService merchantCredits;
	private final TaskReadAccess taskReadAccess;
	private final TaskReadEnrichment taskReadEnrichment;

	public TaskQueryService(TaskRepository tasks, TaskResourceAuthorization taskAuthorization,
			TaskMetricsRepository metrics, AnalyticsRepository analytics, TaskStoreEnrichment storeEnrichment,
			TaskPreviewService previewService,
			com.grassland.marketplace.reputation.MerchantCreditService merchantCredits, TaskReadAccess taskReadAccess,
			TaskReadEnrichment taskReadEnrichment) {
		this.tasks = tasks;
		this.taskAuthorization = taskAuthorization;
		this.metrics = metrics;
		this.analytics = analytics;
		this.storeEnrichment = storeEnrichment;
		this.previewService = previewService;
		this.merchantCredits = merchantCredits;
		this.taskReadAccess = taskReadAccess;
		this.taskReadEnrichment = taskReadEnrichment;
	}

	public Mono<List<Map<String, Object>>> list(String organizationId, String status, String storeId, String q,
			Caller caller) {
		String query = TaskBodies.searchQuery(q);

		if (storeId != null && !storeId.isBlank()) {
			return taskAuthorization.requireScope(caller, organizationId, storeId, "staff")
					.then(tasks.findByStore(organizationId, storeId, status, query).collectList())
					.flatMap(this::enrichTasks);
		}
		// 非 published status 仅本 org merchant 可查（防跨组织草稿/取消泄露）。
		String effectiveStatus = TaskStatus.PUBLISHED.dbValue().equalsIgnoreCase(status) || status.isBlank()
				? TaskStatus.PUBLISHED.dbValue()
				: (caller.isMerchant() && organizationId.equals(caller.organizationId())
						? status
						: TaskStatus.PUBLISHED.dbValue());
		boolean ownerView = caller.isMerchant() && organizationId.equals(caller.organizationId());
		// owner 全量视角：组织级 + 全部门店任务（门店任务的管理入口在主体工作台，
		// 不传 storeId 时不得隐式排除）；推荐官浏览仍只见组织级 published。
		Mono<List<Task>> visibleTasks = ownerView
				? tasks.findAllScopesByOrganization(organizationId, effectiveStatus, query).collectList()
				: taskReadAccess.visibleRecommenderLevel(caller)
						.flatMap(level -> tasks.findByOrganization(organizationId, effectiveStatus, query)
								.filter(task -> !TaskStatus.PUBLISHED.dbValue().equals(task.status())
										|| task.minRecommenderLevel() <= level)
								.collectList());
		return visibleTasks.flatMap(this::enrichTasks);
	}

	public Mono<Map<String, Object>> analytics(String organizationId, String storeId, Instant from, Instant to,
			Caller caller) {
		return taskAuthorization.requireScope(caller, organizationId, TaskBodies.blankToNull(storeId), "staff")
				.flatMap(access -> Mono.zip(metrics.dashboard(access.organizationId(), access.storeId(), from, to),
						analytics.report(access.organizationId(), access.storeId(), from, to)))
				.map(tuple -> TaskBodies.dashboardBody(tuple.getT1(), tuple.getT2()));
	}

	public Mono<Map<String, Object>> usage(String organizationId, Caller caller) {

		// org 归属自查，与发布闸门 1 同口径：不能查别家组织的用量。
		if (!organizationId.equals(caller.organizationId())) {
			return Mono.<Map<String, Object>>error(new MarketplaceException(403, "无权查询该组织用量"));
		}
		MerchantTier tier = MerchantTier.fromDb(caller.permissionTier());
		int maxActive = PublishQuotaPolicy.maxActiveTasks(tier);
		int maxMonthly = PublishQuotaPolicy.maxMonthlyTasks(tier);
		long maxTx = PublishQuotaPolicy.maxTxAmountCents(tier);
		return tasks.countActiveByOrganization(organizationId)
				.flatMap(active -> tasks.countCreatedThisMonthByOrganization(organizationId)
						.map(monthly -> Map.of("organizationId", organizationId, "activeTasks", active, "monthlyTasks",
								monthly, "maxActiveTasks", maxActive, "remainingActiveTasks",
								Math.max(0, maxActive - active), "maxMonthlyTasks", maxMonthly, "remainingMonthlyTasks",
								Math.max(0, maxMonthly - monthly), "maxTxAmountCents", maxTx)));
	}

	public Mono<Map<String, Object>> get(String id, Caller caller) {
		return tasks.findById(id).switchIfEmpty(Mono.error(new MarketplaceException(404, "任务不存在"))).flatMap(task -> {
			// published 对任意 caller 可见；其余状态仅 owner 可见（不泄露 draft/closed/cancelled 存在）。
			boolean publicVisible = TaskStatus.PUBLISHED.dbValue().equals(task.status());
			boolean owner = caller.accountId().equals(task.ownerAccountId());
			if (!publicVisible && task.storeId() != null) {
				return taskAuthorization.requireScope(caller, task.organizationId(), task.storeId(), "staff")
						.then(okWithStore(task, true))
						// 门店授权失败（如推荐官）不立即 403——先看 caller 是否已报名：报名者在任务
						// 关闭（close/cancel/截止扫描）后仍需只读详情，履约提交/条款/争议入口都在
						// 详情读侧（2026-09-11 反馈：closed 后打不开详情导致无法交履约）。公开投影，
						// 不带 progress 经营数据；未报名者原样返回授权错误。
						.onErrorResume(error -> taskReadAccess.appliedBy(caller, id)
								.flatMap(applied -> applied ? okWithStore(task, false) : Mono.error(error)));
			}
			// 任务书 #77 卡 B：storeId 必填后所有任务都是门店级——published 的 owner 视图必须在此短路，
			// 否则掉进公开分支（visibleRecommenderLevel 对商家为空 → 404，owner 打不开自己的任务）。
			if (owner && (task.storeId() == null || publicVisible)) {
				return okWithStore(task, true);
			}
			if (!publicVisible) {
				// 已报名/履约中的推荐官在任务关闭（close/cancel/截止扫描）后仍需只读详情——履约提交、
				// 条款与争议入口都挂在详情读侧（2026-09-11 反馈：closed 后 404 导致无法交履约）。
				// 仅返回公开投影（不带 progress 经营数据）；未参与者维持 404 不泄露存在。
				return taskReadAccess.appliedBy(caller, id)
						.flatMap(applied -> applied
								? okWithStore(task, false)
								: Mono.error(new MarketplaceException(404, "任务不存在")));
			}
			return taskReadAccess.visibleRecommenderLevel(caller).filter(level -> level >= task.minRecommenderLevel())
					.flatMap(level -> okWithStore(task, false))
					.switchIfEmpty(Mono.error(new MarketplaceException(404, "任务不存在")));
		});
	}

	public Mono<Map<String, Object>> preview(String id, Caller caller) {
		return tasks.findById(id).switchIfEmpty(TaskBodies.fail(404, "任务不存在")).flatMap(task -> {
			boolean publicVisible = TaskStatus.PUBLISHED.dbValue().equals(task.status());
			Mono<Boolean> allowed;
			allowed = taskAuthorization.canManage(task, caller)
					.flatMap(manages -> manages
							? Mono.just(true)
							: publicVisible
									? taskReadAccess.visibleRecommenderLevel(caller)
											.map(level -> level >= task.minRecommenderLevel()).defaultIfEmpty(false)
									: taskReadAccess.appliedBy(caller, id));
			return allowed.flatMap(ok -> ok
					? previewService.preview(task)

					: TaskBodies.fail(404, "任务不存在"));
		});
	}

	private Mono<Map<String, Object>> okWithStore(Task task) {
		return okWithStore(task, false);
	}

	private Mono<Map<String, Object>> okWithStore(Task task, boolean withProgress) {
		// withProgress 分支已带 progress；套餐摘要（任务书 #75）叠加在 progress 体或裸体之上。
		Mono<Map<String, Object>> base = withProgress
				? metrics.findProgressByTaskIds(List.of(task.id())).next().map(facts -> {
					Map<String, Object> enriched = TaskBodies.toBody(task);
					enriched.put("progress", TaskBodies.progressBody(task, facts));
					return enriched;
				}).defaultIfEmpty(TaskBodies.toBody(task))
				: Mono.just(TaskBodies.toBody(task));
		Mono<Map<String, Object>> merged = base.flatMap(
				b -> taskReadEnrichment.withCommerceSummaries(List.of(b)).map(list -> list.isEmpty() ? b : list.get(0)))
				// 任务书 #98 C98-04：任务详情内嵌商家信用摘要（样本不足时 insufficientSamples=true、label=null）。
				.flatMap(b -> merchantCredits.compute(task.organizationId()).map(credit -> {
					b.put("merchantCredit", merchantCredits.summaryBody(credit));
					return b;
				}).defaultIfEmpty(b));
		if (task.storeId() == null) {
			return merged;
		}
		return merged.zipWith(storeEnrichment.loadStoreBlocks(List.of(task.storeId()))).map(tuple -> {
			Map<String, Object> enriched = tuple.getT1();
			Map<String, Object> block = tuple.getT2().get(task.storeId());
			if (block != null) {
				enriched.put("store", block);
			}
			return enriched;
		});
	}

	private Mono<List<Map<String, Object>>> enrichTasks(List<Task> rows) {
		return metrics.findProgressByTaskIds(rows.stream().map(Task::id).toList()).collectMap(TaskProgress::taskId)
				.map(progress -> rows.stream().map(task -> {
					Map<String, Object> body = TaskBodies.toBody(task);
					TaskProgress facts = progress.getOrDefault(task.id(), TaskProgress.empty(task.id()));
					body.put("progress", TaskBodies.progressBody(task, facts));
					return body;
				}).toList()).flatMap(taskReadEnrichment::withCommerceSummaries);
	}

}
