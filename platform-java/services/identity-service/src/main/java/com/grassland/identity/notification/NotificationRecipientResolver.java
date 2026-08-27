package com.grassland.identity.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.grassland.identity.event.IdentityEventEnvelope;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 把一条身份域事件解析成「该通知谁」。草场 Slice 12 Stage 2。
 *
 * <p>
 * 全部查 identity 自己的表（app_users / organization_membership /
 * merchant_permission_request）， <b>不跨服务调用</b>——identity 是最上游服务，收件人来源都在本地。
 *
 * <p>
 * 解析规则：
 * <ul>
 * <li>{@code MembershipGranted}：直接通知 payload.accountId。</li>
 * <li>{@code PermissionRequested} /
 * {@code PermissionReviewSlaBreached}：通知平台管理员审核队列。</li>
 * <li>{@code PermissionReviewed}：payload 只有 orgId+decision → 用 aggregateId（=
 * 权限申请 id） 回查 merchant_permission_request.requester_account_id，通知申请人。</li>
 * </ul>
 */
@Component
public class NotificationRecipientResolver {

	private final DatabaseClient db;

	public NotificationRecipientResolver(DatabaseClient db) {
		this.db = db;
	}

	/**
	 * @return 去重后的收件人 accountId 列表；空列表 = 无可送达对象（仍写 inbox，但不产生通知）
	 */
	Mono<java.util.List<String>> resolve(IdentityEventEnvelope envelope) {
		JsonNode payload = envelope.payload();
		return switch (envelope.eventType()) {
			case "MembershipGranted" ->
				text(payload, "accountId").map(java.util.List::of).defaultIfEmpty(java.util.List.of());
			case "PermissionRequested", "PermissionReviewSlaBreached" -> findPlatformAdminAccountIds().collectList();
			case "PermissionReviewed" -> findPermissionRequester(envelope.aggregateId()).map(java.util.List::of)
					.defaultIfEmpty(java.util.List.of());
			// intelligence：组织 AI 预算阈值告警（任务书 #37 登记项）——收件人=组织 owner/admin。
			// 系统触发无操作者，不排除本人；组织 id 非法则空收件人（不重投阻塞分区）。
			case "AiOrgBudgetThresholdCrossed" ->
				text(payload, "organizationId").filter(NotificationRecipientResolver::isUuidText)
						.flatMapMany(this::findOrgManagerAccountIds).collectList().defaultIfEmpty(java.util.List.of());
			// intelligence：个人 AI 预算阈值告警（GL-P3-AI-001 登记项）——收件人=用户本人。
			case "AiPersonalBudgetThresholdCrossed" ->
				text(payload, "accountId").filter(NotificationRecipientResolver::isUuidText).map(java.util.List::of)
						.defaultIfEmpty(java.util.List.of());
			// 任务书 #48：主体代建子账号完成——欢迎通知发新账号本人（凭据线下交接，通知说明账号来路）。
			case "OrgSubAccountCreated" -> Mono.just(accountIds(payload, "accountId"));
			// 任务书 #48：停用/恢复即时生效后知会组织 owner/admin（排除操作者），目标账号本人在列。
			case "MemberSuspensionChanged" -> orgManagersExcluding(payload, "operatorAccountId")
					.flatMap(managers -> {
						java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(managers);
						if (payload.hasNonNull("accountId")) {
							merged.add(payload.get("accountId").asText());
						}
						return Mono.just(java.util.List.copyOf(merged));
					});
			// 任务书 #48：店长代建员工的过审结果——通知被审账号本人。
			case "StaffCreationReviewed" -> Mono.just(accountIds(payload, "accountId"));
			default -> Mono.just(externalRecipients(envelope.eventType(), payload));
		};
	}

	/**
	 * 外部服务事件（marketplace / trust / finance）的收件人。草场 Slice 12 Stage 3。
	 *
	 * <p>
	 * <b>只读 payload 里已有的 accountId</b>——identity 没有 task / dispute / reservation 表，
	 * 也不反向调用下游做领域查询（identity 是最上游服务）。缺字段 → 空列表 → 该事件不产生通知， 但 inbox
	 * 仍记录，不会无限重投。所需字段由发射端在 Stage 3 补齐。
	 */
	private static java.util.List<String> externalRecipients(String eventType, JsonNode payload) {
		return switch (eventType) {
			// 商家侧：报名/撤回/交付进来了，通知任务归属人。
			case "ApplicationSubmitted", "ApplicationWithdrawn", "DeliverableSubmitted" ->
				accountIds(payload, "taskOwnerId");
			// 推荐官侧：报名被接受/被拒绝（#28）。商家是操作者，不通知自己刚做的动作。
			case "ApplicationAccepted", "ApplicationRejected" -> accountIds(payload, "recommenderAccountId");
			// 推荐官任务邀请：收件人已由 marketplace 冻结进 payload，identity 不反查任务域。
			case "TaskRecommenderInvited" -> accountIds(payload, "recommenderAccountId");
			// 任务审核结果：只读 marketplace 发出的任务归属人字段，不反查任务域。
			case "TaskReviewRejected" -> accountIds(payload, "taskOwnerId", "ownerAccountId");
			// #26：满员自动关闭通知任务归属人（payload 直读，不反查任务域——照 TaskReviewRejected 先例）。
			case "TaskClosed" -> accountIds(payload, "taskOwnerId", "ownerAccountId");
			// 推荐官侧：凭证被退回。
			case "DeliverableRejected" -> accountIds(payload, "recommenderAccountId");
			// 人工改判核验结果：商家是唯一需要知道运营结论的一方。
			case "VerificationOverridden" -> accountIds(payload, "taskOwnerId", "ownerAccountId");
			// 双方都关心：核验结果、结算、结算挂起、取消退款（D-03 §5）。
			case "VerificationChecked", "EngagementSettled", "SettlementHeld", "EngagementRefundedOnCancel" ->
				accountIds(payload, "taskOwnerId", "recommenderAccountId");
			// 商家确认窗口（D-03）：进入/临到期/到期自动结算通知双方（商家待确认、推荐官知悉）。
			case "ConfirmationWindowEntered", "ConfirmationWindowExpiring", "AutoSettledOnTimeout" ->
				accountIds(payload, "taskOwnerId", "recommenderAccountId");
			// 商家拒绝系统核实通过履约（D-03）：转客服裁定，双方知悉。
			case "MerchantContested" -> accountIds(payload, "taskOwnerId", "recommenderAccountId");
			// 资金预留失败补偿（押金/赏金余额不足）：通知商家（操作者）为何未接受成功（ADR-D12）。
			case "ApplicationReservationFailed" -> accountIds(payload, "taskOwnerId");
			// 霸王餐押金（ADR-D12）：预付/返还只通知推荐官（WALLET）；补偿入商家 org，双方知悉（ENGAGEMENT）。
			case "FreebieReserved", "FreebieRefunded" -> accountIds(payload, "recommenderAccountId");
			case "FreebieCompensated" -> accountIds(payload, "taskOwnerId", "recommenderAccountId");
			// 争议对方通知：marketplace 派生的 EngagementDisputed 携带已解析的对方账号（草场 Slice 12 缺口补全）。
			case "EngagementDisputed" -> accountIds(payload, "counterpartyAccountId");
			// 争议：只有开启人在 trust 本地表内（对方账号缺口见 docs 路线图第 8 项）。
			case "DisputeAssigned", "AdjudicationReopened", "DisputeDecided", "DisputeAppealed",
					"AdjudicationEscalated", "DisputeFinalized" ->
				accountIds(payload, "openedByAccountId");
			// 审判官投票奖励（任务书 #31 / ADR-D15）/ 现金佣金（ADR-D18）：收件人 = 投票审判官（payload 直读，不反查）。
			case "JudgeVoteRewarded", "JudgeVoteCommissionRewarded" -> accountIds(payload, "judgeAccountId");
			// 资金：payeeAccountId 是用户账号（不是 finance ledger account）。
			case "FundsReserved", "FundsCaptured", "FundsReleased", "FundsReversed", "AccountCredited" ->
				accountIds(payload, "payeeAccountId");
			default -> java.util.List.of();
		};
	}

	/** 按字段顺序取出非空 accountId 并去重（同一账号既是任务归属人又是推荐官时只通知一次）。 */
	private static java.util.List<String> accountIds(JsonNode payload, String... fields) {
		Set<String> deduped = new LinkedHashSet<>();
		for (String field : fields) {
			JsonNode node = payload.get(field);
			if (node != null && node.isTextual() && !node.asText().isBlank()) {
				deduped.add(node.asText());
			}
		}
		return java.util.List.copyOf(deduped);
	}

	/** org 的 owner+admin，排除操作者本人（操作者不需要被通知自己刚做的动作）。 */
	private Mono<java.util.List<String>> orgManagersExcluding(JsonNode payload, String actorField) {
		return text(payload, "organizationId").flatMap(orgId -> {
			Mono<java.util.List<String>> managers = findOrgManagerAccountIds(orgId).collectList();
			return text(payload, actorField).map(actor -> excludeActor(managers, actor)).defaultIfEmpty(managers)
					.flatMap(m -> m);
		}).defaultIfEmpty(java.util.List.of());
	}

	private static Mono<java.util.List<String>> excludeActor(Mono<java.util.List<String>> managers, String actorId) {
		return managers.map(list -> {
			Set<String> deduped = new LinkedHashSet<>(list);
			deduped.remove(actorId);
			return new java.util.ArrayList<>(deduped);
		});
	}

	private static boolean isUuidText(String value) {
		try {
			java.util.UUID.fromString(value);
			return true;
		} catch (IllegalArgumentException error) {
			return false;
		}
	}

	private Flux<String> findOrgManagerAccountIds(String organizationId) {
		return db.sql("""
				SELECT account_id::text FROM organization_membership
				WHERE organization_id = CAST(:org AS uuid) AND role IN ('owner', 'admin')
				""").bind("org", organizationId).map(row -> row.get("account_id", String.class)).all();
	}

	private Flux<String> findPlatformAdminAccountIds() {
		return db.sql("""
				SELECT account_id::text FROM backend_role
				WHERE role = 'platform_admin' ORDER BY granted_at, account_id
				""").map(row -> row.get("account_id", String.class)).all();
	}

	private Mono<String> findPermissionRequester(String requestId) {
		return db.sql("SELECT requester_account_id::text FROM merchant_permission_request WHERE id = CAST(:id AS uuid)")
				.bind("id", requestId).map(row -> row.get("requester_account_id", String.class)).one();
	}

	private static Mono<String> text(JsonNode payload, String field) {
		JsonNode node = payload.get(field);
		return (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank())
				? Mono.empty()
				: Mono.just(node.asText());
	}
}
