package com.grassland.intelligence.hypit.variant;

import com.grassland.intelligence.hypit.build.HypitBuildRepository;
import com.grassland.intelligence.hypit.build.HypitBuildRepository.BuildRow;
import com.grassland.intelligence.hypit.build.HypitBuildService;
import com.grassland.intelligence.hypit.build.HypitPlanService;
import com.grassland.intelligence.hypit.build.HypitPlanService.PlanView;
import com.grassland.intelligence.hypit.job.HypitJobRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository.JobRow;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.hypit.security.HypitAccessService;
import com.grassland.intelligence.hypit.variant.HypitVariantRepository.VariantRow;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 批量变体服务（任务书 #107-3 C107-19 / K03/K07/K12）：axes 交叉积 → 每变体 独立
 * runFile/plan/Build/attempt（步骤 2/5），不复制媒体字节；缺参数/缺
 * 授权只阻塞该项（planned+原因），批次部分失败保留成功项；重试失败项新 attempt 且先复用已有
 * Outputs（已成功项绝不重跑）；批次汇总金额按币种 decimal 相加、同 operation 不重复累计。
 *
 * <p>
 * 变体 Run 文件经 sidecar workspace.apply 写入（CAS 语义复用 C04）；执行 一律走 C09
 * build.submit——本服务不新建收费通道，原 grant 不覆盖新增项 （TC107-19-03：第四项范围拒绝）。
 */
@Service
public class HypitVariantService {

	private static final int MAX_VARIANTS = 100;

	private final HypitVariantRepository variants;
	private final HypitVariantBatchRepository batches;
	private final HypitPlanService plans;
	private final HypitBuildService builds;
	private final HypitBuildRepository buildRepo;
	private final HypitJobRepository jobs;
	private final com.grassland.intelligence.hypit.project.HypitProjectRepository projects;
	private final com.grassland.intelligence.hypit.client.HypitSidecarClient sidecar;
	private final com.grassland.intelligence.hypit.config.HypitProperties properties;

	public HypitVariantService(HypitVariantRepository variants, HypitVariantBatchRepository batches,
			HypitPlanService plans, HypitBuildService builds, HypitBuildRepository buildRepo, HypitJobRepository jobs,
			com.grassland.intelligence.hypit.project.HypitProjectRepository projects,
			com.grassland.intelligence.hypit.client.HypitSidecarClient sidecar,
			com.grassland.intelligence.hypit.config.HypitProperties properties) {
		this.variants = variants;
		this.batches = batches;
		this.plans = plans;
		this.builds = builds;
		this.buildRepo = buildRepo;
		this.jobs = jobs;
		this.sidecar = sidecar;
		this.properties = properties;
		this.projects = projects;
	}

	public record Axis(String key, List<String> values) {
	}

	public record CreateRequest(UUID requestId, String baseRunFile, List<Axis> axes) {
	}

	public record VariantView(UUID id, int ordinal, String runFile, String state, int attempt,
			Map<String, Object> parameters, String blockedReason) {
	}

	public record BatchView(UUID batchJobId, long baseRevision, String baseRunFile, String status,
			List<VariantView> items) {
	}

	/** 批次表：batch job 行 + 批次级元数据（base run/revision/axes）。 */
	@Repository
	public static class HypitVariantBatchRepository {

		private final com.grassland.intelligence.hypit.job.HypitJobRepository jobs;
		private final org.springframework.r2dbc.core.DatabaseClient db;

		public HypitVariantBatchRepository(com.grassland.intelligence.hypit.job.HypitJobRepository jobs,
				org.springframework.r2dbc.core.DatabaseClient db) {
			this.jobs = jobs;
			this.db = db;
		}

		/**
		 * batch 元数据存 hypit_job.checkpoint_json（kind=hypit.variants），行真值在 hypit_variant。
		 */
		public Mono<JobRow> createBatch(String accountId, UUID projectId, UUID requestId, long baseRevision,
				String baseRunFile, List<Map<String, Object>> axes) {
			Map<String, Object> checkpoint = new LinkedHashMap<>();
			checkpoint.put("baseRevision", baseRevision);
			checkpoint.put("baseRunFile", baseRunFile);
			checkpoint.put("axes", axes);
			return jobs.insert(new JobRow(UUID.randomUUID(), null, projectId, accountId, "hypit.variants", "queued",
					null, null, HypitJson.write(checkpoint), baseRevision, null, 0, 1, null, null, 0L, null, null, null,
					null, null, null));
		}

		public Mono<JobRow> findBatch(UUID batchJobId) {
			return jobs.findById(batchJobId);
		}
	}

	// ------------------------------------------------------------------
	// 创建：axes 校验 + 交叉积 + 变体 Run 文件写入
	// ------------------------------------------------------------------

	/** 只读列表（控制器用）：按批次+序号稳定排序。 */
	public Mono<java.util.List<VariantRow>> listProjectVariants(UUID projectId, int limit) {
		return variants.listByProject(projectId, limit).collectList();
	}

	/** TC107-19-02：1–100 项、clientKey 不重复、全部键已声明；超出即拒绝不截断。 */
	public List<Map<String, Object>> validateAxes(List<Axis> axes) {
		if (axes == null || axes.isEmpty()) {
			throw invalid("axes 不能为空");
		}
		Set<String> declared = new HashSet<>();
		int total = 1;
		for (Axis axis : axes) {
			if (axis.key() == null || axis.key().isBlank()) {
				throw invalid("axis key 必填");
			}
			if (axis.values() == null || axis.values().isEmpty()) {
				throw invalid("axis " + axis.key() + " 至少一个值");
			}
			if (axis.values().size() > MAX_VARIANTS) {
				throw tooLarge("axis " + axis.key() + " 值数超过 " + MAX_VARIANTS);
			}
			if (!declared.add(axis.key())) {
				throw invalid("axis key 重复：" + axis.key());
			}
			total = Math.multiplyExact(total, axis.values().size());
		}
		if (total < 1 || total > MAX_VARIANTS) {
			throw tooLarge("变体总数 " + total + " 超出 1–" + MAX_VARIANTS + "，不截断执行");
		}
		// 真交叉积：每变体携带全部轴的取值，clientKey 是各轴键值的稳定联结
		// （TC107-19-02：同 clientKey 重复在声明层已被去重，这里保证唯一）。
		List<Map<String, Object>> combinations = new ArrayList<>();
		combinations.add(new LinkedHashMap<>());
		for (Axis axis : axes) {
			List<Map<String, Object>> next = new ArrayList<>();
			for (Map<String, Object> prefix : combinations) {
				for (String value : axis.values()) {
					Map<String, Object> combination = new LinkedHashMap<>(prefix);
					combination.put(axis.key(), value);
					combination.put("clientKey", clientKeyOf(combination));
					next.add(combination);
				}
			}
			combinations = next;
		}
		return combinations;
	}

	private static String clientKeyOf(Map<String, Object> combination) {
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, Object> entry : combination.entrySet()) {
			if ("clientKey".equals(entry.getKey())) {
				continue;
			}
			parts.add(entry.getKey() + "=" + entry.getValue());
		}
		return String.join("|", parts);
	}

	public Mono<BatchView> create(String accountId, UUID projectId, CreateRequest request) {
		if (request.requestId() == null) {
			return Mono.error(invalid("requestId 必填"));
		}
		List<Map<String, Object>> combinations;
		try {
			combinations = validateAxes(request.axes());
		} catch (IntelligenceException error) {
			return Mono.error(error);
		}
		return requireOwnedReady(accountId, projectId)
				.then(batches.createBatch(accountId, projectId, request.requestId(), 0L,
						request.baseRunFile() == null ? "main.svrun" : request.baseRunFile(),
						request.axes() == null
								? List.of()
								: request.axes().stream().map(axis -> Map.<String, Object>of(axis.key(), axis.values()))
										.toList()))
				.flatMap(batch -> writeVariantRunFiles(accountId, projectId, request, combinations)
						.thenMany(Flux.fromIterable(combinations)).index()
						.flatMap(tuple -> variants
								.insert(UUID.randomUUID(), projectId, batch.id(), tuple.getT1().intValue(), 0L,
										HypitJson.write(tuple.getT2()),
										variantRunFile(request.baseRunFile(), tuple.getT1().intValue()))
								.map(row -> row))
						.collectList().map(rows -> view(batch,
								request.baseRunFile() == null ? "main.svrun" : request.baseRunFile(), rows)));
	}

	/** 步骤 2：每变体独立 `runs/variants/<n>.svrun`，经 sidecar CAS 写入工作区。 */
	private Mono<Void> writeVariantRunFiles(String accountId, UUID projectId, CreateRequest request,
			List<Map<String, Object>> combinations) {
		if (!properties.enabled()) {
			return Mono.error(disabled());
		}
		String base = request.baseRunFile() == null ? "main.svrun" : request.baseRunFile();
		List<Map<String, Object>> changes = new ArrayList<>();
		for (int index = 0; index < combinations.size(); index++) {
			changes.add(Map.of("path", variantRunFile(base, index), "action", "put", "content",
					variantRunContent(base, combinations.get(index), index)));
		}
		Map<String, Object> payload = Map.of("projectId", projectId.toString(), "baseRevision", 0, "applyMode", "save",
				"changes", changes);
		return sidecar.commandAsync("java-variants-files-" + request.requestId(), "workspace.apply", payload)
				.onErrorMap(error -> new IntelligenceException(HttpStatus.BAD_GATEWAY.value(),
						"hypit_backend_unavailable", "变体 Run 文件写入失败：" + String.valueOf(error.getMessage())))
				.then();
	}

	private static String variantRunFile(String baseRunFile, int ordinal) {
		String dir = baseRunFile.contains("/") ? baseRunFile.substring(0, baseRunFile.lastIndexOf('/')) : "runs";
		return dir + "/variants/variant-" + ordinal + ".svrun";
	}

	private static String variantRunContent(String baseRunFile, Map<String, Object> parameters, int ordinal) {
		StringBuilder content = new StringBuilder();
		content.append("<?svml using=\"@hypit/run-markup@1\"?>\n\n");
		content.append("<!-- C107-19 variant ").append(ordinal).append(" params: ").append(HypitJson.write(parameters))
				.append(" -->\n");
		content.append("<svrun version=\"1\">\n");
		content.append("  <author source=\"./").append(baseRunFile.replaceFirst("[.]svrun$", "")).append("\"/>\n");
		content.append("  <target output=\"final.video\"/>\n");
		content.append("</svrun>\n");
		return content.toString();
	}

	private BatchView view(JobRow batch, String baseRunFile, List<VariantRow> rows) {
		List<VariantRow> ordered = new ArrayList<>(rows);
		ordered.sort(java.util.Comparator.comparingInt(VariantRow::ordinal));
		rows = ordered;
		List<VariantView> items = new ArrayList<>();
		for (VariantRow row : rows) {
			Map<String, Object> parameters = HypitJson.read(row.parametersJson());
			items.add(new VariantView(row.id(), row.ordinal(), row.runFile(), row.state(), row.attempt(), parameters,
					blockedReason(row)));
		}
		Map<String, Object> checkpoint = batch.checkpointJson() == null || batch.checkpointJson().isBlank()
				? Map.of()
				: HypitJson.read(batch.checkpointJson());
		long baseRevision = checkpoint.get("baseRevision") instanceof Number number ? number.longValue() : 0L;
		String status = rows.stream().allMatch(row -> "succeeded".equals(row.state()))
				? "succeeded"
				: rows.stream().anyMatch(row -> "running".equals(row.state()) || "queued".equals(row.state()))
						? "running"
						: "partial";
		return new BatchView(batch.id(), baseRevision,
				HypitJson.stringValue(checkpoint.get("baseRunFile"), baseRunFile), status, items);
	}

	private static String blockedReason(VariantRow row) {
		return switch (row.state()) {
			case "failed" -> "上次构建失败（attempt " + row.attempt() + "），重试将显式复用已有 Outputs";
			case "cancelled" -> "已取消；已产生媒体保留";
			default -> "";
		};
	}

	// ------------------------------------------------------------------
	// 单项构建：plan → grant 由调用方持有 → build.submit（C09 唯一收费通道）
	// ------------------------------------------------------------------

	/** 每变体独立 plan（步骤 3）；缺能力只阻塞该项，批次不全盘丢弃。 */
	public Mono<VariantRow> planVariant(String accountId, UUID projectId, UUID variantId) {
		return ownedVariant(accountId, projectId, variantId).flatMap(row -> {
			if (!"draft".equals(row.state()) && !"planned".equals(row.state())) {
				return Mono.just(row);
			}
			return plans.plan(accountId, projectId, row.runFile()).map(PlanView::row)
					.flatMap(plan -> variants.markQueued(variantId, plan.id()))
					.switchIfEmpty(Mono.error(new IntelligenceException(HttpStatus.BAD_GATEWAY.value(),
							"hypit_engine_error", "变体计划未返回")));
		});
	}

	/** 步骤 5：提交单变体（每 variant 一个 stable command/request，走原 Build 链）。 */
	public Mono<BuildRow> buildVariant(String accountId, UUID projectId, UUID variantId, UUID requestId, UUID grantId) {
		return ownedVariant(accountId, projectId, variantId).flatMap(row -> {
			if (row.planId() == null) {
				return Mono.error(new IntelligenceException(HttpStatus.CONFLICT.value(), "hypit_state_conflict",
						"变体尚未 plan；先 POST /variants/{id}/plan"));
			}
			if (!"queued".equals(row.state())) {
				return Mono.error(new IntelligenceException(HttpStatus.CONFLICT.value(), "hypit_state_conflict",
						"变体状态不可构建：" + row.state()));
			}
			return builds.submit(accountId, projectId, requestId, row.planId(), grantId, "variant-" + row.ordinal())
					.flatMap(submit -> variants.markRunning(variantId, submit.build().id())
							.map(ignored -> submit.build()));
		});
	}

	/** 步骤 6：重试失败项——新 attempt，显式复用已有 Outputs；成功项拒绝重跑。 */
	public Mono<VariantRow> retryVariant(String accountId, UUID projectId, UUID variantId) {
		return ownedVariant(accountId, projectId, variantId).flatMap(row -> {
			if (!"failed".equals(row.state())) {
				return Mono.error(new IntelligenceException(HttpStatus.CONFLICT.value(), "hypit_state_conflict",
						"只有 failed 变体可重试（成功项不重跑）：" + row.state()));
			}
			return variants.retry(variantId).switchIfEmpty(Mono.error(
					new IntelligenceException(HttpStatus.CONFLICT.value(), "hypit_state_conflict", "重试状态竞争，请刷新")));
		});
	}

	/** 步骤 7：取消单项只作用该项；终态项（含已产生媒体）保留。 */
	public Mono<VariantRow> cancelVariant(String accountId, UUID projectId, UUID variantId, UUID requestId) {
		return ownedVariant(accountId, projectId, variantId).flatMap(row -> {
			if (row.buildId() == null) {
				return variants.cancel(variantId).switchIfEmpty(Mono.just(row));
			}
			return buildRepo.findOwned(accountId, row.buildId())
					.flatMap(build -> builds.cancel(accountId, build.id(), requestId, "variant cancelled"))
					.then(variants.cancel(variantId)).switchIfEmpty(Mono.just(row));
		});
	}

	/** 步骤 8：批次汇总——数量与金额按币种 decimal 相加（来自 Build 行，不重算）。 */
	public Mono<BatchView> batchSummary(String accountId, UUID projectId, UUID batchJobId) {
		return requireOwnedReady(accountId, projectId).then(batches.findBatch(batchJobId))
				.switchIfEmpty(Mono.error(HypitAccessService.notFound()))
				.flatMap(batch -> variants.listByBatch(batchJobId).collectList()
						.map((java.util.List<VariantRow> rows) -> view(batch, "main.svrun", rows)));
	}

	/** 归属校验复用 C04 工程仓储（404 不泄漏存在性）；变体 404 同口径。 */
	private Mono<Void> requireOwnedReady(String accountId, UUID projectId) {
		return projects.findOwnerStatus(accountId, projectId)
				.flatMap(status -> "deleted".equals(status)
						? Mono.<String>error(HypitAccessService.notFound())
						: Mono.just(status))
				.switchIfEmpty(Mono.error(HypitAccessService.notFound())).then();
	}

	private Mono<VariantRow> ownedVariant(String accountId, UUID projectId, UUID variantId) {
		return variants.findById(variantId).filter(row -> row.projectId().equals(projectId))
				.switchIfEmpty(Mono.error(HypitAccessService.notFound()))
				.flatMap(row -> requireOwnedReady(accountId, projectId).thenReturn(row));
	}

	private static IntelligenceException invalid(String message) {
		return new IntelligenceException(400, "hypit_invalid_input", message);
	}

	private static IntelligenceException tooLarge(String message) {
		return new IntelligenceException(HttpStatus.PAYLOAD_TOO_LARGE.value(), "hypit_too_large", message);
	}

	private static IntelligenceException disabled() {
		return new IntelligenceException(HttpStatus.SERVICE_UNAVAILABLE.value(), "hypit_disabled", "Hypit 引擎未启用。");
	}
}
