package com.grassland.intelligence.creationstudio.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.creationassistant.CreationDraft;
import com.grassland.intelligence.creationassistant.CreationDraftService;
import com.grassland.intelligence.creationstudio.CreationRecipeCatalog;
import com.grassland.intelligence.creationstudio.CreationRecipeCatalog.RecipeDefinition;
import com.grassland.intelligence.creationstudio.CreationStudioContextService;
import com.grassland.intelligence.creationstudio.CreationStudioContextService.DraftContext;
import com.grassland.intelligence.creationstudio.CreationStudioProperties;
import com.grassland.intelligence.creationstudio.CreationVisualPresetCatalog;
import com.grassland.intelligence.creationstudio.source.SourceDocument;
import com.grassland.intelligence.creationstudio.source.SourceDocumentRepository;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.crypto.EnvelopeEncryption;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-05（API101-08～11）：服务端视觉计划、修订与确认。
 *
 * <p>
 * 任务书 #103 C103-21：构建（prepare 全链）与修订（PATCH/confirm）已按职责搬移至
 * {@link VisualPlanBuildService} 与 {@link VisualPlanRevisionService}；本类保留 wire
 * 层 （命令/结果 record、§6.5 解析缺省与共享限制常量、hashCanonical）并以委托保持既有 API， 行为零变更。
 *
 * <ul>
 * <li>prepare：先落 preparing 占位（同键幂等），onPrepared 先持久化 runId 再请求模型； 最终组装 prompt
 * 以信封加密完整留档（无 KEK → 503 fail-closed，不落明文）。</li>
 * <li>模型输出严格解析：根／项拒未知字段，假 blockId、非逐字 criticalText、错角色、数量不符 →
 * failed（STUDIO_INVALID_PLAN），不做第二次隐形修复调用；itemId／cardId／position 由服务端铸造。</li>
 * <li>PATCH：客户端提交完整 document，逐项校验后追加不可变 revision 并推进指针、清确认；
 * 来源、模板与策略不允许切换（换策略须新建计划）；position 按提交顺序重排，身份不变。</li>
 * <li>confirm：只确认当前 revision（行锁 + 草稿版本／来源 hash／正文 hash 核验），记录确认时
 * draftVersion、contentHash 与 actor；正文漂移 → STUDIO_PLAN_STALE，须重建或确认新计划。</li>
 * </ul>
 *
 * <p>
 * 锁顺序遵守 §7.3.2（draft → plan → apply）；本卡确认／PATCH 只锁 plan 行，草稿侧以
 * 版本核验代替写锁（本卡不写草稿）。
 */
@Service
public class VisualPlanService {

	private static final int MAX_SELECTED_CODE_POINTS = 8_000;
	static final int MAX_TITLE = 60;
	static final int MAX_BULLETS = 5;
	static final int MAX_BULLET_LENGTH = 80;
	static final int MAX_ILLUSTRATION = 1_000;
	static final int MAX_CRITICAL = 10;
	static final int MAX_CRITICAL_LENGTH = 128;
	static final int MAX_EXPLANATION = 500;
	static final Set<String> ASPECTS = Set.of("3:4", "9:16", "1:1", "16:9", "2.35:1");
	private static final Set<String> STRATEGIES = Set.of("story", "information", "visual");

	private final VisualPlanBuildService build;
	private final VisualPlanRevisionService revision;

	public VisualPlanService(VisualPlanBuildService build, VisualPlanRevisionService revision) {
		this.build = build;
		this.revision = revision;
	}

	// ---------- 任务书 #103 C103-21：构建/修订已按职责搬移，facade 委托保持既有公共 API ----------

	public Mono<Outcome> prepare(ServerWebExchange exchange, Caller caller, PrepareCommand command) {
		return build.prepare(exchange, caller, command);
	}

	public Mono<Outcome> loadOwned(UUID id, Caller caller, Integer revision) {
		return build.loadOwned(id, caller, revision);
	}

	public Mono<VisualPlan.PlanRow> loadOwnedRow(UUID id, Caller caller) {
		return build.loadOwnedRow(id, caller);
	}

	public Mono<Outcome> patch(Caller caller, UUID id, PatchCommand command) {
		return revision.patch(caller, id, command);
	}

	public Mono<Outcome> confirm(Caller caller, UUID id, ConfirmCommand command) {
		return revision.confirm(caller, id, command);
	}

	// ---- 命令 ----

	/** API101-08 请求（wire 解析在 Controller）；style 三段各自可空（部分 VisualStyle）。 */
	public record PrepareCommand(UUID requestId, UUID draftId, int expectedDraftVersion, String recipeId,
			String recipeVersion, UUID sourceDocumentId, String sourceContentHash, List<String> selectedBlockIds,
			String strategy, Integer itemCount, String styleId, String layoutId, String paletteId,
			String targetAspect) {

		String requestHash() {
			Map<String, Object> canonical = new TreeMap<>();
			canonical.put("draftId", draftId.toString());
			canonical.put("expectedDraftVersion", expectedDraftVersion);
			canonical.put("recipeId", recipeId);
			canonical.put("recipeVersion", recipeVersion);
			canonical.put("sourceDocumentId", sourceDocumentId.toString());
			canonical.put("sourceContentHash", sourceContentHash);
			canonical.put("selectedBlockIds", selectedBlockIds == null ? List.of() : selectedBlockIds);
			canonical.put("strategy", strategy == null ? "" : strategy);
			canonical.put("itemCount", itemCount == null ? 0 : itemCount);
			canonical.put("styleId", styleId == null ? "" : styleId);
			canonical.put("layoutId", layoutId == null ? "" : layoutId);
			canonical.put("paletteId", paletteId == null ? "" : paletteId);
			canonical.put("targetAspect", targetAspect == null ? "" : targetAspect);
			return hashCanonical(canonical);
		}
	}

	public record Outcome(VisualPlan.PlanRow plan, boolean preparing, Map<String, Object> document, boolean stale,
			Map<String, Object> replayBody) {
		public Outcome(VisualPlan.PlanRow plan, boolean preparing, Map<String, Object> document, boolean stale) {
			this(plan, preparing, document, stale, null);
		}
	}

	public record ConfirmCommand(UUID requestId, UUID draftId, int expectedDraftVersion, int expectedRevision,
			String sourceContentHash) {
	}

	public record PatchCommand(UUID requestId, int expectedRevision, Map<String, Object> documentRaw) {
	}

	// ---- 解析与缺省（§6.5 推荐缺省） ----

	/** 模板解析：未知／版本不符／未开放／平台形式不适用 → 400（不回退其他模板）。 */
	static RecipeDefinition resolveRecipe(String recipeId, String recipeVersion, CreationDraft draft) {
		RecipeDefinition recipe = CreationRecipeCatalog.byId(recipeId);
		if (recipe == null) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "未知模板：" + recipeId);
		}
		if (recipeVersion != null && !recipeVersion.equals(recipe.version())) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT",
					"模板 " + recipeId + " 不存在版本 " + recipeVersion + "（当前 " + recipe.version() + "）");
		}
		if (!recipe.enabled()) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "模板「" + recipe.label() + "」暂未开放");
		}
		if (draft.platform() != null && !recipe.platformIds().contains(draft.platform())) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "模板「" + recipe.label() + "」不适用于当前平台");
		}
		if (draft.contentForm() != null && !recipe.contentForms().contains(draft.contentForm())) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "模板「" + recipe.label() + "」不适用于当前内容形式");
		}
		return recipe;
	}

	static List<SourceDocument.Block> selectBlocks(SourceDocument document, List<String> selectedBlockIds) {
		Map<String, SourceDocument.Block> byId = new LinkedHashMap<>();
		for (var block : document.blocks()) {
			byId.put(block.id(), block);
		}
		List<SourceDocument.Block> selected;
		if (selectedBlockIds == null || selectedBlockIds.isEmpty() || selectedBlockIds.size() > 200
				|| new LinkedHashSet<>(selectedBlockIds).size() != selectedBlockIds.size()) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "请选择 1～200 个不重复的来源块");
		} else {
			selected = new ArrayList<>();
			for (String blockId : selectedBlockIds) {
				var block = byId.get(blockId);
				if (block == null) {
					throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "选择的来源块不存在：" + blockId);
				}
				selected.add(block);
			}
		}
		int total = 0;
		for (var block : selected) {
			total += block.text().codePointCount(0, block.text().length());
		}
		if (total > MAX_SELECTED_CODE_POINTS) {
			throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED",
					"选择范围合计超过 " + MAX_SELECTED_CODE_POINTS + " 字符，请缩小处理范围");
		}
		return selected;
	}

	/**
	 * §6.5 推荐缺省：图卡 min(6,max(2,1+ceil(非空块/2)))；文章配图 min(6,max(1,标题块+1))； 封面
	 * 1；均不超模板上下限。显式数量在上下限内时原样采用（不覆盖用户选择）。
	 */
	static int resolveItemCount(Integer explicit, RecipeDefinition recipe, List<SourceDocument.Block> blocks) {
		if (explicit != null) {
			if (explicit < recipe.minItems() || explicit > recipe.maxItems()) {
				throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED",
						"项目数须在 " + recipe.minItems() + "～" + recipe.maxItems() + " 之间（模板上限）");
			}
			return explicit;
		}
		int recommended = switch (recipe.id()) {
			case "social-card-series" -> {
				int nonEmpty = 0;
				for (var block : blocks) {
					if (!block.text().isBlank()) {
						nonEmpty++;
					}
				}
				yield Math.min(6, Math.max(2, 1 + ceilHalf(nonEmpty)));
			}
			case "article-visuals" -> {
				int headings = 0;
				for (var block : blocks) {
					if ("heading".equals(block.kind())) {
						headings++;
					}
				}
				yield Math.min(6, Math.max(1, headings + 1));
			}
			case "cover-only" -> 1;
			default -> Math.max(1, recipe.minItems());
		};
		return Math.min(Math.max(recommended, recipe.minItems()), recipe.maxItems());
	}

	private static int ceilHalf(int value) {
		return (value + 1) / 2;
	}

	/**
	 * §6.5 推荐缺省：story 须本人有确认过的体验型（story）计划经历；visual 须 ≥3 既有素材 且所选文字 ≤1,200 字；其余
	 * information。显式策略必须在模板支持集内。
	 */
	static String resolveStrategy(String explicit, RecipeDefinition recipe, boolean hasStoryHistory,
			DraftContext context, List<SourceDocument.Block> blocks) {
		if (explicit != null) {
			if (!STRATEGIES.contains(explicit) || !recipe.supportedStrategies().contains(explicit)) {
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "模板不支持该策划策略");
			}
			return explicit;
		}
		if (recipe.supportedStrategies().contains("story") && hasStoryHistory
				&& context.draft().workspace().get("inputs") instanceof Map<?, ?> inputs
				&& inputs.get("brief") instanceof Map<?, ?> brief
				&& ("experience".equals(brief.get("contentType")) || "experience".equals(brief.get("purpose")))) {
			return "story";
		}
		int total = 0;
		for (var block : blocks) {
			total += block.text().codePointCount(0, block.text().length());
		}
		if (recipe.supportedStrategies().contains("visual") && context.draft().resultAssetIds() != null
				&& context.draft().resultAssetIds().size() >= 3 && total <= 1_200) {
			return "visual";
		}
		return recipe.supportedStrategies().contains("information")
				? "information"
				: recipe.supportedStrategies().get(0);
	}

	/** 部分样式补全：缺省取目录内首个完整预设（种草清单），未知 ID 一律 400（不猜测）。 */
	static VisualPlan.Style resolveStyle(String styleId, String layoutId, String paletteId) {
		var preset = CreationVisualPresetCatalog.preset("xhs-cute-list");
		String resolvedStyle = styleId != null ? styleId : preset.styleId();
		String resolvedLayout = layoutId != null ? layoutId : preset.layoutId();
		String resolvedPalette = paletteId != null ? paletteId : preset.paletteId();
		if (CreationVisualPresetCatalog.style(resolvedStyle) == null) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "未知视觉风格：" + resolvedStyle);
		}
		if (CreationVisualPresetCatalog.layout(resolvedLayout) == null) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "未知画面布局：" + resolvedLayout);
		}
		if (CreationVisualPresetCatalog.palette(resolvedPalette) == null) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "未知配色：" + resolvedPalette);
		}
		return new VisualPlan.Style(resolvedStyle, resolvedLayout, resolvedPalette);
	}

	static String resolveAspect(String explicit, RecipeDefinition recipe) {
		if (explicit == null) {
			return recipe.defaultAspect() == null ? "3:4" : recipe.defaultAspect();
		}
		if (!ASPECTS.contains(explicit)) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "目标画幅不合法：" + explicit);
		}
		return explicit;
	}

	static List<String> allowedRoles(RecipeDefinition recipe) {
		return switch (recipe.id()) {
			case "social-card-series" -> List.of("cover", "content", "summary");
			case "article-visuals" -> List.of("cover", "illustration");
			default -> List.of("cover");
		};
	}

	static String styleText(String styleId) {
		var style = CreationVisualPresetCatalog.style(styleId);
		return style == null ? styleId : style.label() + "（" + style.prompt() + "）";
	}

	static String layoutText(String layoutId) {
		var layout = CreationVisualPresetCatalog.layout(layoutId);
		return layout == null ? layoutId : layout.label() + "（" + layout.prompt() + "）";
	}

	static String paletteText(String paletteId) {
		var palette = CreationVisualPresetCatalog.palette(paletteId);
		return palette == null ? paletteId : palette.label() + "（" + palette.prompt() + "）";
	}

	// ---- 公共小工具 ----

	static String hashCanonical(Map<String, Object> canonical) {
		return PlanJson.sha256(PlanJson.json(canonical));
	}
}
