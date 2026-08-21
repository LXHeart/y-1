package com.grassland.marketplace.workflow;

import com.grassland.http.ManagedWebClientFactory;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.security.ServiceAssertionIssuer;
import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskRequirements;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 履约提交文本硬门槛客户端（ADR-D16 D6 登记项落地）：提交交付物时把全部自由文本（评论文本 commentText /
 * 备注 note）一次同步过 intelligence 词库（`POST /internal/content-safety/submission-check`，服务断言），
 * 逐字段返回 blocked/advisory 结论。
 *
 * <p>姿态：**blocked 才拒**（400「XX未通过内容安全检查」，advisory 对齐 ADR-D16 D6—— 词库 low/medium 命中
 * 不拦截，商家人审截图仍可见原文）；intelligence 不可用 → fail-open 放行并告警（提交是低频操作，词库检查是
 * 附加闸门而非唯一闸门）。
 *
 * <p>guardSubmission() 恒发射一个 {@link SubmissionCheck}（无字段需检/fail-open = 无明细的 skip 态），
 * low/medium advisory 明细随结论返回——提交链路据此按字段落人工复核队列。
 */
@Component
public class IntelligenceSubmissionSafetyClient {

	private static final Logger log = LoggerFactory.getLogger(IntelligenceSubmissionSafetyClient.class);
	private static final ParameterizedTypeReference<Envelope<CheckData>> CHECK_TYPE = new ParameterizedTypeReference<>() {
	};

	/** 与 intelligence 端约定 + comment_safety_review.field 取值一致的检查字段名。 */
	public static final String FIELD_COMMENT = "comment";
	public static final String FIELD_NOTE = "note";

	private static final Map<String, String> FIELD_LABELS = Map.of(
			FIELD_COMMENT, "评论内容", FIELD_NOTE, "备注");

	private final WebClient webClient;
	private final ServiceAssertionIssuer issuer;
	private final String headerName;

	public IntelligenceSubmissionSafetyClient(ServiceAssertionIssuer issuer,
			@Value("${intelligence.service.base-url:http://intelligence-service:8086}") String baseUrl,
			@Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
		this.webClient = ManagedWebClientFactory.create(IntelligenceSubmissionSafetyClient.class, baseUrl);
		this.issuer = issuer;
		this.headerName = headerName;
	}

	/** skip 态：无需检查（无自由文本）或 fail-open（审核服务不可用）。无 advisory 明细。 */
	public static SubmissionCheck skip() {
		return new SubmissionCheck(null, null, null);
	}

	/**
	 * 提交闸门：评论文本（仅评论互动任务携带）与备注（任意任务）同步词库检查，任一 blocked → 400（字段级
	 * 文案）；advisory 明细随结论按字段返回供复核留痕。恒发射（不 empty）——调用链需要结论续流。
	 */
	public Mono<SubmissionCheck> guardSubmission(Task task, String commentText, String note) {
		return Mono.defer(() -> {
			Map<String, String> texts = new LinkedHashMap<>();
			if (isCommentTask(task) && commentText != null && !commentText.isBlank()) {
				texts.put("commentText", commentText.trim());
			}
			if (note != null && !note.isBlank()) {
				texts.put("note", note.trim());
			}
			if (texts.isEmpty()) {
				return Mono.just(skip());
			}
			return check(task.organizationId(), texts).flatMap(result -> result.blocked()
					? Mono.<SubmissionCheck>error(new MarketplaceException(400,
							blockedFieldLabels(result) + "未通过内容安全检查，请修改后提交"))
					: Mono.just(result));
		});
	}

	private static boolean isCommentTask(Task task) {
		TaskRequirements.Interaction interaction = task.requirements() == null
				? null
				: task.requirements().interaction();
		return TaskRequirements.isInteractionForm(task.contentForm()) && interaction != null
				&& "comment".equals(interaction.actionType());
	}

	private static String blockedFieldLabels(SubmissionCheck result) {
		StringBuilder labels = new StringBuilder();
		if (result.comment() != null && result.comment().blocked()) {
			labels.append(FIELD_LABELS.get(FIELD_COMMENT));
		}
		if (result.note() != null && result.note().blocked()) {
			if (labels.length() > 0) {
				labels.append('、');
			}
			labels.append(FIELD_LABELS.get(FIELD_NOTE));
		}
		return labels.toString();
	}

	private Mono<SubmissionCheck> check(String organizationId, Map<String, String> texts) {
		return webClient.post().uri("/internal/content-safety/submission-check")
				.header(headerName, issuer.issueForOrg(organizationId, "grassland-intelligence"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(texts).exchangeToMono(response -> {
					if (response.statusCode().is2xxSuccessful()) {
						return response.bodyToMono(CHECK_TYPE).map(Envelope::data)
								.map(IntelligenceSubmissionSafetyClient::toCheck);
					}
					// fail-open：审核服务不可用不拦截提交（词库是附加闸门），告警留痕。
					log.warn("submission safety check unavailable status={} fields={}",
							response.statusCode().value(), texts.keySet());
					return response.releaseBody().then(Mono.just(skip()));
				}).onErrorResume(error -> {
					log.warn("submission safety check failed open", error);
					return Mono.just(skip());
				});
	}

	private static SubmissionCheck toCheck(CheckData data) {
		if (data == null || data.fields() == null) {
			return skip();
		}
		return new SubmissionCheck(fieldCheck(data.fields().get(FIELD_COMMENT)),
				fieldCheck(data.fields().get(FIELD_NOTE)), data.lexiconVersion());
	}

	private static FieldCheck fieldCheck(FieldVerdict raw) {
		if (raw == null) {
			return null;
		}
		return new FieldCheck(raw.blocked(),
				raw.details() == null ? List.of() : List.copyOf(raw.details()));
	}

	/** intelligence 信封。 */
	private record Envelope<T>(boolean success, T data) {
	}

	/** intelligence data 原样（fields: {field: {blocked, findings, details}} + lexiconVersion）。 */
	record CheckData(Map<String, FieldVerdict> fields, String lexiconVersion) {
	}

	record FieldVerdict(boolean blocked, int findings, List<AdvisoryFinding> details) {
	}

	/**
	 * 词库检查结论（按字段）：comment/note 为 null = 该字段未检查（非评论任务/无文本/fail-open）； blocked =
	 * high 命中（guard 已转 400）；advisory = low/medium 命中明细（不拦截，提交链路按字段落
	 * comment_safety_review 人工复核队列）。
	 */
	public record SubmissionCheck(FieldCheck comment, FieldCheck note, String lexiconVersion) {

		public boolean blocked() {
			return (comment != null && comment.blocked()) || (note != null && note.blocked());
		}
	}

	public record FieldCheck(boolean blocked, List<AdvisoryFinding> details) {
	}

	/** low/medium 命中明细（与 intelligence 端 details 字段一一对应；match 词不下发）。 */
	public record AdvisoryFinding(String category, String severity, String advice) {
	}
}
