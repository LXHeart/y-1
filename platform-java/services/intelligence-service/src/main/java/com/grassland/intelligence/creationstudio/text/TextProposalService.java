package com.grassland.intelligence.creationstudio.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.creationassistant.CreationDraft;
import com.grassland.intelligence.creationstudio.CreationStudioContextService;
import com.grassland.intelligence.creationstudio.CreationStudioProperties;
import com.grassland.intelligence.creationstudio.StudioRequestValidator;
import com.grassland.intelligence.creationstudio.source.SourceDocumentRepository;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-04（API101-05～07）：可预览、显式应用的文本建议。
 *
 * <ul>
 * <li>prepare：先落 preparing 记录（同键幂等），再经 onPrepared 在模型请求前持久化 runId；
 * 断线重放不再次请求模型。一次调用只产生一个建议（不在后台循环优化）。</li>
 * <li>结果字段白名单：adapt-body 只允许 title/body；suggest-metadata 只允许 title/summary；
 * 未请求字段必须为 null，模型编造字段 → failed（不可应用）。</li>
 * <li>apply：校验有效期／源 hash／版本（当前 version 须同时等于 expectedDraftVersion 与
 * baseDraftVersion），一次原子写入正文及适用元数据；旧稿进历史版本。</li>
 * </ul>
 */
@Service
public class TextProposalService {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	static final Duration MODEL_TIMEOUT = Duration.ofSeconds(90);
	static final Duration PROPOSAL_TTL = Duration.ofMinutes(30);
	static final Duration ZOMBIE_PREPARING = Duration.ofSeconds(120);
	private static final int MAX_BODY_CODE_POINTS = 30_000;
	private static final int MAX_CHANGES = 20;
	private static final int MAX_CHANGE_LENGTH = 300;
	private static final int MAX_SELECTED_BLOCKS = 200;
	private static final int MAX_SELECTED_CODE_POINTS = 8_000;

	private final TextProposalRepository proposals;
	private final CreationStudioContextService contexts;
	private final SourceDocumentRepository sources;
	private final CreationStudioProperties properties;
	private final FrozenTextExecutionService frozenText;
	private final com.grassland.intelligence.creationassistant.CreationDraftService drafts;
	private final Clock clock;
	private final org.springframework.beans.factory.ObjectProvider<com.grassland.crypto.EnvelopeEncryption> encryption;

	@org.springframework.beans.factory.annotation.Autowired
	public TextProposalService(TextProposalRepository proposals, CreationStudioContextService contexts,
			SourceDocumentRepository sources, CreationStudioProperties properties,
			FrozenTextExecutionService frozenText,
			com.grassland.intelligence.creationassistant.CreationDraftService drafts,
			org.springframework.beans.factory.ObjectProvider<com.grassland.crypto.EnvelopeEncryption> encryption) {
		this(proposals, contexts, sources, properties, frozenText, drafts, Clock.systemUTC(), encryption);
	}

	TextProposalService(TextProposalRepository proposals, CreationStudioContextService contexts,
			SourceDocumentRepository sources, CreationStudioProperties properties,
			FrozenTextExecutionService frozenText,
			com.grassland.intelligence.creationassistant.CreationDraftService drafts, Clock clock,
			org.springframework.beans.factory.ObjectProvider<com.grassland.crypto.EnvelopeEncryption> encryption) {
		this.proposals = proposals;
		this.contexts = contexts;
		this.sources = sources;
		this.properties = properties;
		this.frozenText = frozenText;
		this.drafts = drafts;
		this.clock = clock;
		this.encryption = encryption;
	}

	/** API101-05 请求（wire 解析在 Controller）。 */
	public record PrepareCommand(UUID requestId, UUID draftId, int expectedDraftVersion, String action,
			UUID sourceDocumentId, String sourceContentHash, List<String> selectedBlockIds, String instructions) {

		String requestHash() {
			Map<String, Object> canonical = new TreeMap<>();
			canonical.put("draftId", draftId.toString());
			canonical.put("expectedDraftVersion", expectedDraftVersion);
			canonical.put("action", action);
			canonical.put("sourceDocumentId", sourceDocumentId == null ? null : sourceDocumentId.toString());
			canonical.put("sourceContentHash", sourceContentHash);
			canonical.put("selectedBlockIds", selectedBlockIds == null ? List.of() : selectedBlockIds);
			canonical.put("instructions", instructions == null ? "" : instructions);
			return hashCanonical(canonical);
		}
	}

	public record Outcome(TextProposalRepository.ProposalRow proposal, boolean preparing) {
	}

	/** API101-07 应用命令。 */
	public record ApplyCommand(UUID requestId, int expectedDraftVersion, Set<String> fields) {
	}

	public record ApplyOutcome(CreationDraft draft, Integer appliedDraftVersion, boolean alreadyApplied) {
	}

	public Mono<Outcome> prepare(ServerWebExchange exchange, Caller caller, PrepareCommand command) {
		String hash = command.requestHash();
		return proposals.findByOwnerAndRequestId(caller.accountId(), command.requestId().toString())
				.flatMap(row -> drafts.loadOwned(row.draftId().toString(), caller.accountId()).then(replay(row, hash)))
				.switchIfEmpty(Mono.defer(() -> contexts.loadOwnedDraftContext(command.draftId().toString(), caller)
						.flatMap(context -> prepareRow(caller, command, context, hash))
						.flatMap(prepared -> runModel(exchange, caller, command, prepared))));
	}

	private record Prepared(TextProposalRepository.ProposalRow row, String lfContent, String baseContentHash,
			String sourceText, CreationStudioContextService.DraftContext context) {
	}

	private Mono<Prepared> prepareRow(Caller caller, PrepareCommand command,
			CreationStudioContextService.DraftContext context, String hash) {
		if (!properties.isWritesEnabled())
			return Mono.error(new IntelligenceException(404, "STUDIO_DISABLED", "创作工作台写入暂未开放"));
		if (context.draft().status() == com.grassland.intelligence.creationassistant.DraftStatus.ARCHIVED)
			return Mono.error(new IntelligenceException(409, "STUDIO_RESOURCE_LOCKED", "归档草稿只读"));
		if (context.draft().version() != command.expectedDraftVersion()) {
			return Mono.error(new IntelligenceException(409, "STUDIO_VERSION_CONFLICT", "草稿版本已变化，请刷新后重试"));
		}
		return contexts.taskBinding(context.draft(), caller.accountId())
				.then(loadSelectedText(caller, command, context)).flatMap(sourceText -> {
					var crypto = encryption.getIfAvailable();
					if (crypto == null)
						return Mono
								.error(new IntelligenceException(503, "STUDIO_DEPENDENCY_UNAVAILABLE", "建议记录加密设施不可用"));
					String finalPrompt = systemPrompt(command.action()) + "\n"
							+ json(Map.of("action", command.action(), "instructions",
									command.instructions() == null ? "" : command.instructions(), "sourceText",
									sourceText));
					var row = new TextProposalRepository.ProposalRow(UUID.randomUUID(), caller.accountId(),
							command.draftId(), command.requestId().toString(), hash, command.action(), "preparing",
							context.draft().version(), context.baseContentHash(), command.sourceDocumentId(),
							json(command.selectedBlockIds()), inputSnapshot(command, context),
							crypto.encrypt(finalPrompt),
							com.grassland.intelligence.creationstudio.plan.PlanJson.sha256(finalPrompt), Map.of(), null,
							null, clock.instant().atOffset(ZoneOffset.UTC),
							clock.instant().plus(ZOMBIE_PREPARING).atOffset(ZoneOffset.UTC), null, null, null);
					return proposals.insertPlaceholder(row).flatMap(inserted -> inserted
							? Mono.just(new Prepared(row, context.lfContent(), context.baseContentHash(), sourceText,
									context))
							: proposals.findByOwnerAndRequestId(caller.accountId(), command.requestId().toString())
									.flatMap(existing -> replay(existing, hash)
											.map(ignored -> new Prepared(existing, null, null, null, context))));
				});
	}

	/** 来源选择：无 sourceDocumentId 时用当前正文（LF）；有则按 owner+draft 校验读取原文。 */
	private Mono<String> loadSelectedText(Caller caller, PrepareCommand command,
			CreationStudioContextService.DraftContext context) {
		if (command.sourceDocumentId() == null) {
			return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "source 不能为空"));
		}
		return sources.findById(command.sourceDocumentId())
				.filter(document -> caller.accountId().equals(document.ownerAccountId())
						&& document.draftId().equals(command.draftId()))
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "来源文档不属于当前草稿")))
				.map(document -> {
					if (!document.normalizedMarkdown().equals(context.lfContent()))
						throw new IntelligenceException(409, "STUDIO_PLAN_STALE", "请按当前正文重新冻结来源");
					if (command.sourceContentHash() != null
							&& !command.sourceContentHash().equals(document.contentHash())) {
						throw new IntelligenceException(409, "STUDIO_PLAN_STALE", "来源正文已变化，请重新核对");
					}
					return selectedText(document, command.selectedBlockIds());
				});
	}

	/** 按 selectedBlockIds 切块取文（块跨度为 normalizedMarkdown 的 code point 区间）；空选择=全文。 */
	static String selectedText(com.grassland.intelligence.creationstudio.source.SourceDocument document,
			List<String> selectedBlockIds) {
		if (selectedBlockIds == null || selectedBlockIds.isEmpty()) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "请选择处理范围");
		}
		if (selectedBlockIds.size() > MAX_SELECTED_BLOCKS
				|| new java.util.HashSet<>(selectedBlockIds).size() != selectedBlockIds.size())
			throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", "处理范围最多 200 个不重复段落");
		Map<String, com.grassland.intelligence.creationstudio.source.SourceDocument.Block> byId = new LinkedHashMap<>();
		for (var block : document.blocks()) {
			byId.put(block.id(), block);
		}
		List<String> parts = new ArrayList<>();
		int total = 0;
		for (String blockId : selectedBlockIds) {
			var block = byId.get(blockId);
			if (block == null) {
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "选择的来源块不存在：" + blockId);
			}
			parts.add(block.text());
			total += block.text().codePointCount(0, block.text().length());
		}
		if (total > MAX_SELECTED_CODE_POINTS) {
			throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED",
					"选择范围合计超过 " + MAX_SELECTED_CODE_POINTS + " 字符，请缩小处理范围");
		}
		return String.join("\n\n", parts);
	}

	private Mono<Outcome> runModel(ServerWebExchange exchange, Caller caller, PrepareCommand command,
			Prepared prepared) {
		if (prepared.lfContent() == null) {
			return replay(prepared.row(), command.requestHash());
		}
		List<ChatMessage> messages = List.of(ChatMessage.system(systemPrompt(command.action())),
				ChatMessage.user(userPrompt(command, prepared)));
		return Mono.defer(() -> contexts.executeText(exchange, caller, prepared.context(), messages, 4096,
				command.action().equals("adapt-body")
						? CreditFeature.ARTICLE_GENERATION
						: CreditFeature.CREATION_ASSISTANT,
				MODEL_TIMEOUT, (runId, actual) -> {
					String prompt = com.grassland.intelligence.creationstudio.plan.PlanJson.json(actual);
					return proposals.capturePrompt(prepared.row().id(), runId,
							encryption.getIfAvailable().encrypt(prompt),
							com.grassland.intelligence.creationstudio.plan.PlanJson.sha256(prompt));
				}, completion -> {
					Map<String, Object> result = parseResult(command.action(), completion.content());
					if (prepared.context().draft()
							.contentMode() == com.grassland.intelligence.creationassistant.DraftContentMode.ANSWER
							&& result.get("title") != null)
						throw new IntelligenceException(502, "STUDIO_INVALID_PLAN", "回答模式不接受文章标题建议");
					result.put("sourceBlockIds", command.selectedBlockIds());
					return result;
				}))
				.flatMap(traced -> preserveUnselected(command, traced.value()).flatMap(result -> proposals
						.completeReady(prepared.row().id(), json(result), traced.runId(),
								clock.instant().plus(PROPOSAL_TTL).atOffset(ZoneOffset.UTC))
						.then(proposals.findById(prepared.row().id()))))
				.map(row -> new Outcome(row, false)).onErrorResume(error -> {
					IntelligenceException failure = error instanceof IntelligenceException original
							? original
							: new IntelligenceException(503, "STUDIO_PROVIDER_FAILED", "模型暂不可用，请稍后重试");
					if ("STUDIO_INVALID_PLAN".equals(failure.code())) {
						// 模型输出违约（确定性失败）：保留运行与失败记录，按 200 返回 failed 建议。
						return proposals.completeInvalid(prepared.row().id(), null)
								.then(proposals.findById(prepared.row().id())).map(row -> new Outcome(row, false));
					}
					return proposals
							.completeFailed(prepared.row().id(),
									failure.code() == null ? "STUDIO_PROVIDER_FAILED" : failure.code(), null)
							.then(Mono.error(failure));
				});
	}

	/** 字段白名单解析：未请求字段必须缺失或 null；编造字段 → 502 不可应用。 */
	static Map<String, Object> parseResult(String action, String content) {
		try {
			JsonNode root = MAPPER.readTree(stripCodeFence(content));
			if (!root.isObject())
				throw new IllegalArgumentException("建议必须为对象");
			var names = root.fieldNames();
			while (names.hasNext())
				if (!Set.of("title", "body", "summary", "changes").contains(names.next()))
					throw new IllegalArgumentException("建议包含未定义字段");
			Map<String, Object> result = new LinkedHashMap<>();
			boolean allowBody = "adapt-body".equals(action);
			boolean allowSummary = "suggest-metadata".equals(action);
			result.put("title", textOrNull(root.path("title"), 64));
			result.put("body", allowBody ? bodyOrNull(root.path("body")) : null);
			result.put("summary", allowSummary ? textOrNull(root.path("summary"), 120) : null);
			if (!allowBody && root.hasNonNull("body")) {
				throw new IllegalArgumentException("模型返回了未请求的正文改编");
			}
			if (!allowSummary && root.hasNonNull("summary")) {
				throw new IllegalArgumentException("模型返回了未请求的摘要");
			}
			List<String> changes = new ArrayList<>();
			if (!root.path("changes").isArray() || root.path("changes").size() > MAX_CHANGES)
				throw new IllegalArgumentException("变更说明必须为至多 20 项数组");
			for (JsonNode item : root.path("changes")) {
				if (!item.isTextual())
					throw new IllegalArgumentException("变更说明必须为文本");
				String text = item.asText();
				if (text.codePointCount(0, text.length()) > MAX_CHANGE_LENGTH) {
					throw new IllegalArgumentException("变更说明过长");
				}
				changes.add(text);
			}
			result.put("changes", changes);
			result.put("sourceBlockIds", List.of());
			if (result.get("title") == null && result.get("body") == null && result.get("summary") == null) {
				throw new IllegalArgumentException("模型未返回任何建议字段");
			}
			return result;
		} catch (IntelligenceException error) {
			throw error;
		} catch (Exception error) {
			throw new IntelligenceException(502, "STUDIO_INVALID_PLAN", "模型返回不合法建议");
		}
	}

	private static String bodyOrNull(JsonNode node) {
		if (node.isNull() || node.isMissingNode()) {
			return null;
		}
		if (!node.isTextual()) {
			throw new IntelligenceException(502, "STUDIO_INVALID_PLAN", "模型返回不合法建议");
		}
		String body = node.asText();
		if (body.codePointCount(0, body.length()) > MAX_BODY_CODE_POINTS) {
			throw new IntelligenceException(502, "STUDIO_INVALID_PLAN", "改编正文超过上限");
		}
		return body;
	}

	private Mono<Map<String, Object>> preserveUnselected(PrepareCommand command, Map<String, Object> result) {
		if (!(result.get("body") instanceof String body))
			return Mono.just(result);
		return sources.findById(command.sourceDocumentId()).map(source -> {
			if (command.selectedBlockIds().size() == source.blocks().size())
				return result;
			String raw = source.normalizedMarkdown();
			StringBuilder combined = new StringBuilder();
			int offset = 0;
			boolean inserted = false;
			for (var block : source.blocks()) {
				if (!command.selectedBlockIds().contains(block.id()))
					continue;
				int start = raw.offsetByCodePoints(0, block.startCodePoint());
				int end = raw.offsetByCodePoints(0, block.endCodePoint());
				combined.append(raw, offset, start);
				if (!inserted) {
					combined.append(body);
					inserted = true;
				}
				offset = end;
			}
			combined.append(raw.substring(offset));
			String merged = combined.toString();
			if (merged.codePointCount(0, merged.length()) > MAX_BODY_CODE_POINTS)
				throw new IntelligenceException(502, "STUDIO_INVALID_PLAN", "改编结果与保留段落合计超过上限");
			result.put("body", merged);
			return result;
		});
	}

	private static String textOrNull(JsonNode node, int max) {
		if (node.isNull() || node.isMissingNode()) {
			return null;
		}
		if (!node.isTextual() || node.asText().isBlank()
				|| node.asText().codePointCount(0, node.asText().length()) > max) {
			throw new IntelligenceException(502, "STUDIO_INVALID_PLAN", "建议文本字段不合法或超过上限");
		}
		return node.asText();
	}

	private static String stripCodeFence(String content) {
		String text = content == null ? "" : content.trim();
		if (text.startsWith("```")) {
			int firstNewLine = text.indexOf('\n');
			if (firstNewLine > 0) {
				text = text.substring(firstNewLine + 1);
			}
			if (text.endsWith("```")) {
				text = text.substring(0, text.length() - 3);
			}
		}
		return text.trim();
	}

	public Mono<TextProposalRepository.ProposalRow> loadOwned(UUID id, Caller caller) {
		return proposals.findById(id).filter(row -> caller.accountId().equals(row.ownerAccountId()))
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "建议不存在")))
				.flatMap(row -> drafts.loadOwned(row.draftId().toString(), caller.accountId())
						.then(proposals.expirePreparing(id)).then(proposals.findById(id)));
	}

	/** API101-07：应用（同事务：锁建议+锁草稿 → 校验 → 历史版本+新草稿+应用结果）。 */
	public Mono<ApplyOutcome> apply(Caller caller, UUID proposalId, ApplyCommand command) {
		return loadOwned(proposalId, caller).flatMap(row -> drafts.withStudioDraftLock(row.draftId().toString(), caller,
				current -> applyLocked(caller, proposalId, command)));
	}

	private Mono<ApplyOutcome> applyLocked(Caller caller, UUID proposalId, ApplyCommand command) {
		return proposals.lockById(proposalId).filter(row -> caller.accountId().equals(row.ownerAccountId()))
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "建议不存在"))).flatMap(row -> {
					if ("applied".equals(row.status())) {
						Object expected = row.applyResult().get("expectedDraftVersion");
						if (!command.requestId().toString().equals(row.applyRequestId())
								|| !(row.applyResult().get("fields") instanceof List<?> fields)
								|| !new java.util.HashSet<>(fields).equals(command.fields())
								|| !(expected instanceof Number version)
								|| version.intValue() != command.expectedDraftVersion())
							return Mono.error(
									new IntelligenceException(409, "STUDIO_OPERATION_CONFLICT", "该建议已由其他应用请求处理"));
						// 同键重放：返回已应用版本，不再追加版本。
						return drafts.loadOwned(row.draftId().toString(), caller.accountId())
								.map(current -> new ApplyOutcome(current,
										row.appliedDraftVersion() == null
												? current.version()
												: row.appliedDraftVersion(),
										true));
					}
					if (!properties.isWritesEnabled())
						return Mono.error(new IntelligenceException(404, "STUDIO_DISABLED", "创作工作台写入暂未开放"));
					if (!"ready".equals(row.status())) {
						return Mono.error(new IntelligenceException(409, "STUDIO_RESOURCE_LOCKED", "建议当前状态不可应用"));
					}
					if (!row.expiresAt().toInstant().isAfter(clock.instant())) {
						return Mono.error(new IntelligenceException(409, "STUDIO_PLAN_STALE", "建议已过期，请重新生成"));
					}
					if (command.fields() == null || command.fields().isEmpty()
							|| !Set.of("title", "body", "summary").containsAll(command.fields())) {
						return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "应用字段不合法"));
					}
					// §6.5：知乎 answer 模式 title 建议必须为 null，申请应用 title 返回 400；
					// apply 校验当前 version 须同时等于 expectedDraftVersion 与建议的 baseDraftVersion。
					return contexts.loadOwnedDraftContext(row.draftId().toString(), caller).flatMap(context -> {
						boolean answerMode = context.draft().contentMode() != null
								&& "answer".equals(context.draft().contentMode().db());
						if (answerMode && command.fields().contains("title")) {
							return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "回答模式不支持应用标题建议"));
						}
						if (!context.baseContentHash().equals(row.baseContentHash())) {
							return Mono
									.error(new IntelligenceException(409, "STUDIO_PLAN_STALE", "正文已变化，建议不再适用，请重新生成"));
						}
						return drafts.applyStudioMutation(row.draftId().toString(), caller,
								command.expectedDraftVersion(), current -> {
									if (current.version() != row.baseDraftVersion()) {
										throw new IntelligenceException(409, "STUDIO_VERSION_CONFLICT",
												"建议基于旧版正文，请刷新后重新生成");
									}
									return mutateDraft(current, row, command.fields());
								})
								.flatMap(mutated -> proposals
										.markApplied(row.id(), mutated.version(), command.requestId().toString(),
												json(Map.of("fields", List.copyOf(command.fields()),
														"expectedDraftVersion", command.expectedDraftVersion())))
										.map(updated -> updated > 0
												? new ApplyOutcome(mutated, mutated.version(), false)
												: new ApplyOutcome(mutated, mutated.version(), true)));
					});
				});
	}

	/**
	 * title→articleTitle（导航 title
	 * 不动）；body→content；summary→workspace.delivery.summary。
	 */
	private static CreationDraft mutateDraft(CreationDraft current, TextProposalRepository.ProposalRow row,
			Set<String> fields) {
		Map<String, Object> result = row.result();
		for (String field : fields)
			if (result.get(field) == null)
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "所选字段在建议中为空：" + field);
		String title = fields.contains("title") ? (String) result.get("title") : null;
		String body = fields.contains("body") ? (String) result.get("body") : null;
		String summary = fields.contains("summary") ? (String) result.get("summary") : null;
		if (title == null && body == null && summary == null) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "所选字段在建议中为空");
		}
		Map<String, Object> workspace = new LinkedHashMap<>(
				current.workspace() == null ? Map.of() : current.workspace());
		if (summary != null) {
			Map<String, Object> delivery = new LinkedHashMap<>(
					workspace.get("delivery") instanceof Map<?, ?> existing ? castMap(existing) : Map.of());
			delivery.put("summary", summary);
			workspace.put("delivery", delivery);
		}
		Map<String, Object> inputs = new LinkedHashMap<>(
				workspace.get("inputs") instanceof Map<?, ?> existing ? castMap(existing) : Map.of());
		Map<String, Object> studio = new LinkedHashMap<>(
				inputs.get("studio") instanceof Map<?, ?> existing ? castMap(existing) : Map.of());
		studio.put("schemaVersion", 1);
		studio.put("lastProposalId", row.id().toString());
		inputs.put("studio", studio);
		workspace.put("inputs", inputs);
		return new CreationDraft(current.id(), current.ownerAccountId(), current.organizationId(), current.title(),
				current.sourceType(), current.taskId(), current.taskVersion(), current.storeId(), current.platform(),
				current.contentForm(), current.topic(), title != null ? title : current.articleTitle(),
				current.outline(), body != null ? body : current.content(), current.contentMode(),
				current.questionText(), current.questionRef(), current.status(), current.version(), current.createdAt(),
				current.updatedAt(), current.deletedAt(), workspace, current.resultAssetIds(), current.runIds());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castMap(Map<?, ?> map) {
		return (Map<String, Object>) map;
	}

	private Mono<Outcome> replay(TextProposalRepository.ProposalRow row, String hash) {
		if (!row.requestHash().equals(hash)) {
			return Mono.error(new IntelligenceException(409, "STUDIO_OPERATION_CONFLICT", "同一 requestId 已用于不同请求"));
		}
		return proposals.expirePreparing(row.id()).then(proposals.findById(row.id()))
				.map(saved -> new Outcome(saved, "preparing".equals(saved.status())));
	}

	static String systemPrompt(String action) {
		if ("adapt-body".equals(action)) {
			return "你是中文内容改编编辑。基于用户提供的原稿与平台改写正文：保留事实、数字、价格与引用，"
					+ "不虚构经历。只输出 JSON：{\"title\": string|null, \"body\": string, "
					+ "\"changes\": [string]}。title 仅在被要求时给出；changes 每条不超过 300 字。不要输出其他字段或解释。";
		}
		return "你是中文内容编辑。基于用户提供的原稿提出标题与摘要建议：数字与事实必须来自原文，"
				+ "不得编造。只输出 JSON：{\"title\": string|null, \"summary\": string|null, "
				+ "\"changes\": [string]}。title 至多 64 字，summary 至多 120 字。不要输出其他字段或解释。";
	}

	static String userPrompt(PrepareCommand command, Prepared prepared) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("action", command.action());
		payload.put("instructions", command.instructions() == null ? "" : command.instructions());
		payload.put("sourceText", prepared.sourceText());
		return json(payload);
	}

	private static String inputSnapshot(PrepareCommand command, CreationStudioContextService.DraftContext context) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("sourceContentHash", command.sourceContentHash());
		snapshot.put("platform", context.draft().platform());
		snapshot.put("contentForm", context.draft().contentForm());
		snapshot.put("contentMode", context.draft().contentMode() == null ? null : context.draft().contentMode().db());
		return json(snapshot);
	}

	private static String json(Object value) {
		try {
			return MAPPER.writeValueAsString(value);
		} catch (Exception error) {
			throw new IllegalStateException("建议序列化失败", error);
		}
	}

	/** 规范化哈希工具（与 CreationStudioContextService 同款实现）。 */
	static String hashCanonical(Map<String, Object> canonical) {
		try {
			var digest = java.security.MessageDigest.getInstance("SHA-256");
			return java.util.HexFormat.of().formatHex(digest.digest(MAPPER.writeValueAsBytes(canonical)));
		} catch (Exception error) {
			throw new IllegalStateException("请求摘要计算失败", error);
		}
	}
}
