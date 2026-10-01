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
						.flatMap(tuple -> variants.insert(UUID.randomUUID(), projectId, batch.id(),
								tuple.getT1().intValue(), 0L, HypitJson.write(tuple.getT2()),
								variantRunFile(request.baseRunFile(), tuple.getT1().intValue())))
						// C107F2-37（缺陷 U 连带）：批次跟踪行由创建链自己收口——此前靠
						// agent worker 的泛化认领「顺手」置 succeeded（kind 过滤修复后
						// 无人认领），这里按真实结果终态化。
						.collectList()
						.flatMap(rows -> jobs.updateState(batch.id(), "succeeded", null, null).thenReturn(rows))
						.onErrorResume(error -> jobs.updateState(batch.id(), "failed", "hypit_variant_batch_failed",
								String.valueOf(error.getMessage())).then(Mono.error(error)))
						.map(rows -> view(batch, request.baseRunFile() == null ? "main.svrun" : request.baseRunFile(),
								rows)));
	}

	/**
	 * 步骤 2：每变体独立 `runs/variants/<n>.svrun`，经 sidecar CAS 写入工作区。 107-fix-2 C37
	 * 修复两处：baseRevision 必须取工程当前 head（写死 0 对任何已 provision 的工程都撞「base revision 0 is
	 * stale」，变体文件从未落盘）；sidecar 200 回执的 state=failed 必须显式闸死（与 C35 delete 同族——静默吞掉会让
	 * 批次「成功」而 build 阶段 ENOENT）。
	 */
	private Mono<Void> writeVariantRunFiles(String accountId, UUID projectId, CreateRequest request,
			List<Map<String, Object>> combinations) {
		if (!properties.enabled()) {
			return Mono.error(disabled());
		}
		String base = request.baseRunFile() == null ? "main.svrun" : request.baseRunFile();
		return projects.findOwned(accountId, projectId).switchIfEmpty(Mono.error(HypitAccessService.notFound()))
				.flatMap(project -> resolveVariantAuthorSource(projectId, base, request.requestId())
						.flatMap(authorSource -> {
							List<Map<String, Object>> pending = new ArrayList<>();
							for (int index = 0; index < combinations.size(); index++) {
								pending.add(Map.of("path", variantRunFile(base, index), "action", "put", "content",
										variantRunContent(authorSource, combinations.get(index), index)));
							}
							return sidecar.commandAsync("java-variants-files-" + request.requestId(), "workspace.apply",
									Map.of("projectId", projectId.toString(), "baseRevision", project.revision(),
											"applyMode", "save", "changes", pending));
						}))
				// C107F2-32：维护窗 503 保持原语义上浮（hypit_maintenance 可重试），
				// 不得折叠成 BAD_GATEWAY 伪装成后端故障。
				.onErrorMap(error -> error instanceof com.grassland.intelligence.security.IntelligenceException
						? error
						: new IntelligenceException(HttpStatus.BAD_GATEWAY.value(), "hypit_backend_unavailable",
								"变体 Run 文件写入失败：" + String.valueOf(error.getMessage())))
				.flatMap(receipt -> "succeeded".equals(receipt.state())
						? convergeVariantRevision(projectId, receipt)
						: Mono.error(new IntelligenceException(HttpStatus.BAD_GATEWAY.value(), "hypit_engine_error",
								"变体 Run 文件写入被引擎拒绝：" + receipt.state()
										+ (receipt.error() == null ? "" : " " + String.valueOf(receipt.error())))));
	}

	/**
	 * C107F2-37（缺陷 H）：workspace.apply 把变体 Run 文件落成新 revision 后，必须像
	 * changesets.convergeApplied 一样推进 hypit_project.revision/head——否则 PG 永远 停在旧
	 * revision，planVariant 按过期 revision 冻结快照规划（variant-*.svrun 在 该快照尚不存在）→ runner
	 * realpath ENOENT → plan/build 全链 502。 同 requestId 重放命中 broker
	 * 同一回执：同值重写幂等（advanceRevision 无 revision 前卫）。
	 */
	private Mono<Void> convergeVariantRevision(UUID projectId,
			com.grassland.intelligence.hypit.client.HypitSidecarClient.SidecarCommand receipt) {
		Map<String, Object> result = HypitJson.mapValue(receipt.result());
		long revision = HypitJson.longValue(result.get("revision"), 0);
		String manifestHash = HypitJson.stringValue(result.get("manifestHash"), "");
		if (revision < 1) {
			return Mono.error(new IntelligenceException(HttpStatus.BAD_GATEWAY.value(), "hypit_engine_error",
					"变体 Run 文件写入回执缺 revision"));
		}
		return projects.advanceRevision(projectId, revision, manifestHash).then();
	}

	private static String variantRunFile(String baseRunFile, int ordinal) {
		String dir = baseRunFile.contains("/") ? baseRunFile.substring(0, baseRunFile.lastIndexOf('/')) : "runs";
		return dir + "/variants/variant-" + ordinal + ".svrun";
	}

	/**
	 * C107F2-37（缺陷 J）：变体 Run 文件位于 `<baseDir>/variants/` 子目录，而引擎按 Run 文件所在目录解析
	 * `<author source>`（upstream run.md：source 强制指向 .svml Author Source）。历史实现写死
	 * `./<baseRunFile 去扩展名>`——根目录 main 会被解析 成不存在的 runs/variants/main，runner
	 * realpath ENOENT → plan/build 全链 502。 这里经 sidecar 读基 Run 文件的真实 author
	 * source，换算成「变体文件目录 → author 文件」的工作区相对路径；基 Run 缺 author 或路径越出工作区根都显式失败， 不猜默认值。
	 */
	private Mono<String> resolveVariantAuthorSource(UUID projectId, String baseRunFile, UUID requestId) {
		return sidecar
				.commandAsync("java-variants-base-" + requestId, "workspace.read",
						Map.of("projectId", projectId.toString(), "path", baseRunFile))
				.map(com.grassland.intelligence.hypit.client.HypitSidecarClient.SidecarCommand::result).map(result -> {
					String content = HypitJson.stringValue(HypitJson.mapValue(result).get("content"), "");
					java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("<author\\s+source=\"([^\"]+)\"")
							.matcher(content);
					if (!matcher.find()) {
						throw new IntelligenceException(HttpStatus.BAD_GATEWAY.value(), "hypit_engine_error",
								"基 Run 文件缺 <author> 条目，无法生成变体：" + baseRunFile);
					}
					return relativeAuthorSource(baseRunFile, matcher.group(1));
				});
	}

	/** author source 按基 Run 文件目录解析出工作区内路径，再相对变体文件目录改写。 */
	static String relativeAuthorSource(String baseRunFile, String authorSource) {
		String baseDir = baseRunFile.contains("/") ? baseRunFile.substring(0, baseRunFile.lastIndexOf('/') + 1) : "";
		java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
		for (String segment : (baseDir + authorSource.replace('\\', '/')).split("/")) {
			if (segment.isEmpty() || segment.equals(".")) {
				continue;
			}
			if (segment.equals("..")) {
				if (stack.pollLast() == null) {
					throw new IntelligenceException(HttpStatus.BAD_GATEWAY.value(), "hypit_engine_error",
							"author source 越出工作区根：" + authorSource);
				}
				continue;
			}
			stack.addLast(segment);
		}
		if (stack.isEmpty()) {
			throw new IntelligenceException(HttpStatus.BAD_GATEWAY.value(), "hypit_engine_error",
					"author source 解析为空：" + authorSource);
		}
		String absolute = String.join("/", stack);
		String variantDir = variantRunFile(baseRunFile, 0);
		variantDir = variantDir.substring(0, variantDir.lastIndexOf('/') + 1);
		String[] from = variantDir.split("/");
		String[] to = absolute.split("/");
		int common = 0;
		while (common < from.length && common < to.length && from[common].equals(to[common])) {
			common++;
		}
		StringBuilder relative = new StringBuilder();
		for (int index = common; index < from.length; index++) {
			relative.append("../");
		}
		relative.append(String.join("/", java.util.Arrays.copyOfRange(to, common, to.length)));
		return relative.toString();
	}

	private static String variantRunContent(String authorSource, Map<String, Object> parameters, int ordinal) {
		StringBuilder content = new StringBuilder();
		content.append("<?svml using=\"@hypit/run-markup@1\"?>\n\n");
		content.append("<!-- C107-19 variant ").append(ordinal).append(" params: ").append(HypitJson.write(parameters))
				.append(" -->\n");
		content.append("<svrun version=\"1\">\n");
		content.append("  <author source=\"").append(authorSource).append("\"/>\n");
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
			if (!"failed".equals(row.state()) && !"cancelled".equals(row.state())) {
				return Mono.error(new IntelligenceException(HttpStatus.CONFLICT.value(), "hypit_state_conflict",
						"只有 failed/cancelled 变体可重试（成功项不重跑）：" + row.state()));
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
