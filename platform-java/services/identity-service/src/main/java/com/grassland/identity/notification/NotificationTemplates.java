package com.grassland.identity.notification;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通知文案与分类（纯函数，无副作用）。草场 Slice 12 Stage 2。
 *
 * <p>
 * 把「事件类型 → 面向人的标题/正文/跳转」集中在一处，便于审阅与本地化。 <b>不把他人邮箱写进别人的通知</b>——通知 body
 * 只描述「发生了什么事」，具体对象（哪个组织、哪个任务） 放 {@code payload} 供前端渲染，避免成为信息泄露通道。
 *
 * <p>
 * {@link #template} 返回 {@code null} 表示该事件不产生通知（非关注类型）。
 */
public final class NotificationTemplates {

	static final String LINK_INVITATIONS = "/me/invitations";
	static final String LINK_PERMISSION = "/me/organizations";
	static final String LINK_ENGAGEMENTS = "/me/engagements";
	static final String LINK_TASK_INVITATIONS = "/me/task-invitations";
	static final String LINK_TASK_REVIEW = "/me/task-review";
	static final String LINK_DISPUTES = "/me/disputes";
	static final String LINK_WALLET = "/me/wallet";

	private NotificationTemplates() {
	}

	/**
	 * @return 该事件的静态文案模板；{@code null} = 不产生通知（消费者据此判 IGNORED，不写 inbox）
	 */
	static Template template(String eventType, JsonNode payload) {
		return switch (eventType) {
			case "MembershipInvited" -> new Template(NotificationCategory.INVITATION, "你收到了一份组织邀请", "有一个组织邀请你加入，点击查看",
					LINK_INVITATIONS, orgPayload(payload));
			case "MembershipInvitationReminder" -> new Template(NotificationCategory.INVITATION, "组织邀请提醒：仍待你接受",
					"你有一份组织邀请尚未接受，即将过期，点击处理", LINK_INVITATIONS, orgPayload(payload));
			case "MembershipInvitationAccepted", "MembershipInvitationDeclined" ->
				new Template(NotificationCategory.INVITATION, "你的邀请已有回应", "你发出的组织邀请已被接受或谢绝，点击查看", LINK_INVITATIONS,
						orgPayload(payload));
			case "MembershipInvitationRevoked" ->
				new Template(NotificationCategory.INVITATION, "你的邀请已被撤销", "发给你的组织邀请已被撤销", null, orgPayload(payload));
			case "MembershipGranted" -> new Template(NotificationCategory.INVITATION, "你已加入组织", "你已被加入一个组织",
					LINK_INVITATIONS, grantedPayload(payload));
			case "PermissionRequested" -> new Template(NotificationCategory.PERMISSION, "收到商家权限升级申请",
					"有商家提交权限升级申请，待平台审核", LINK_PERMISSION, orgPayload(payload));
			case "PermissionReviewSlaBreached" -> new Template(NotificationCategory.PERMISSION, "商家权限审核已超时",
					"一条商家权限申请已超过审核时限，请尽快处理", LINK_PERMISSION, orgPayload(payload));
			case "PermissionReviewed" -> new Template(NotificationCategory.PERMISSION, "你的权限申请已审核", "你的商家权限升级申请已有审核结果",
					LINK_PERMISSION, orgPayload(payload));
			default -> externalTemplate(eventType, payload);
		};
	}

	/**
	 * 外部服务（marketplace / trust / finance）事件文案。草场 Slice 12 Stage 3。
	 *
	 * <p>
	 * 正文只描述「发生了什么」，具体标的（taskId / disputeId / 金额）放 payload 给前端渲染； 不把对方账号写进正文。
	 */
	private static Template externalTemplate(String eventType, JsonNode payload) {
		return switch (eventType) {
			// ---------- marketplace：履约 ----------
			case "ApplicationSubmitted" -> new Template(NotificationCategory.ENGAGEMENT, "有新的报名", "你的任务收到一份新报名，待你处理",
					LINK_ENGAGEMENTS, taskPayload(payload));
			case "TaskRecommenderInvited" -> new Template(NotificationCategory.ENGAGEMENT, "你收到一份任务邀请",
					"有商家邀请你参与任务，点击查看并报名", LINK_TASK_INVITATIONS, taskPayload(payload));
			case "TaskReviewRejected" -> new Template(NotificationCategory.ENGAGEMENT, "任务审核未通过",
					"你的任务未通过平台审核，请查看驳回原因并修改后重新提交", LINK_TASK_REVIEW, taskPayload(payload));
			case "ApplicationWithdrawn" -> new Template(NotificationCategory.ENGAGEMENT, "有报名被撤回", "你的任务有一份报名已被撤回",
					LINK_ENGAGEMENTS, taskPayload(payload));
			// marketplace：报名结果只通知推荐官——商家是操作者，不需要被告知自己刚做的动作。
			case "ApplicationAccepted" -> new Template(NotificationCategory.ENGAGEMENT, "你的报名已被接受",
					"商家已接受你的任务报名，点击查看任务并开始创作", LINK_ENGAGEMENTS, taskPayload(payload));
			case "ApplicationRejected" -> new Template(NotificationCategory.ENGAGEMENT, "你的报名未被接受",
					"商家未接受你的任务报名，可继续浏览其他任务", LINK_ENGAGEMENTS, taskPayload(payload));
			case "DeliverableSubmitted" -> new Template(NotificationCategory.ENGAGEMENT, "收到交付凭证", "有推荐官提交了履约凭证，待你核验",
					LINK_ENGAGEMENTS, taskPayload(payload));
			case "DeliverableRejected" -> new Template(NotificationCategory.ENGAGEMENT, "你的凭证被退回",
					"你提交的履约凭证被退回，请查看原因后重新提交", LINK_ENGAGEMENTS, taskPayload(payload));
			case "VerificationOverridden" -> new Template(NotificationCategory.ENGAGEMENT, "履约核验已人工复核",
					"运营已对该履约的自动核验结果作出人工结论，请查看详情", LINK_ENGAGEMENTS, taskPayload(payload));
			case "VerificationChecked" -> new Template(NotificationCategory.ENGAGEMENT, "履约核验有结果", "该履约的凭证核验已出结果",
					LINK_ENGAGEMENTS, taskPayload(payload));
			case "EngagementSettled" -> new Template(NotificationCategory.ENGAGEMENT, "履约已结算", "该履约已完成结算",
					LINK_ENGAGEMENTS, taskPayload(payload));
			// marketplace：商家取消任务，未提交凭证的履约预留按资金来源退还（D-03 §5 + ADR-D12 D6）。
			case "EngagementRefundedOnCancel" -> {
				String body = "recommender".equals(stringField(payload, "refundDirection"))
						? "商家取消了任务，你的霸王餐押金已全额退还"
						: "商家取消了任务，未提交凭证的履约预留已退还商家";
				yield new Template(NotificationCategory.ENGAGEMENT, "任务已取消，履约资金已退还", body, LINK_ENGAGEMENTS,
						taskPayload(payload));
			}
			// marketplace：#26 满员自动关闭只通知任务归属人；商家手动关闭是自身操作，不通知（D11）。
			case "TaskClosed" -> {
				if (!"slots_full".equals(stringField(payload, "closeReason"))) {
					yield null;
				}
				yield new Template(NotificationCategory.ENGAGEMENT, "任务名额已满，已自动关闭", "你的任务报名名额已满，系统已自动关闭报名",
						LINK_ENGAGEMENTS, taskPayload(payload));
			}
			// marketplace：资金预留失败补偿（如推荐官押金余额不足），通知商家为何未接受成功（ADR-D12 验收 #2）。
			case "ApplicationReservationFailed" -> new Template(NotificationCategory.ENGAGEMENT, "接受报名未成功",
					"一笔报名的资金预留未成功（如余额不足），报名已回退待处理，请查看原因", LINK_ENGAGEMENTS, taskPayload(payload));
			case "SettlementHeld" -> new Template(NotificationCategory.ENGAGEMENT, "结算被挂起", "该履约的结算被暂时挂起，待条件满足后继续",
					LINK_ENGAGEMENTS, taskPayload(payload));
			// marketplace：商家确认窗口（D-03）
			case "ConfirmationWindowEntered" -> new Template(NotificationCategory.ENGAGEMENT, "请确认履约",
					"有推荐官提交了履约凭证，请在确认窗口内确认或退回，逾期未操作将自动确认结算", LINK_ENGAGEMENTS, taskPayload(payload));
			// marketplace：确认窗口临到期提醒（D-03 §1 剩余 24h 强提醒）。
			case "ConfirmationWindowExpiring" -> new Template(NotificationCategory.ENGAGEMENT, "履约确认窗口即将到期",
					"该履约的确认窗口即将到期，逾期未操作将自动确认结算，请尽快处理", LINK_ENGAGEMENTS, taskPayload(payload));
			case "AutoSettledOnTimeout" -> new Template(NotificationCategory.ENGAGEMENT, "履约已自动结算",
					"商家确认窗口到期未操作，系统已自动确认结算该履约", LINK_ENGAGEMENTS, taskPayload(payload));
			// marketplace：商家拒绝系统核实通过的履约，已直送客服终审（D-03 §2）。
			case "MerchantContested" -> new Template(NotificationCategory.DISPUTE, "履约异议已转客服裁定",
					"商家对系统核实通过的履约发起异议，平台客服将在时限内裁定", LINK_DISPUTES, disputePayload(payload));
			// ---------- trust：争议 ----------
			// marketplace 派生：有人对一笔履约开了争议，通知对方（草场 Slice 12 缺口补全）。
			case "EngagementDisputed" -> {
				String body = "recommender".equals(stringField(payload, "openedByRole"))
						? "推荐官对你发布的任务发起了一起争议，请查看"
						: "商家对你提交的履约发起了一起争议，请查看";
				yield new Template(NotificationCategory.DISPUTE, "你被发起了一起争议", body, LINK_DISPUTES,
						disputePayload(payload));
			}
			case "DisputeAssigned" -> new Template(NotificationCategory.DISPUTE, "争议已进入审判", "你参与的争议已组建审判庭并开始投票",
					LINK_DISPUTES, disputePayload(payload));
			case "AdjudicationReopened" -> new Template(NotificationCategory.DISPUTE, "争议已重新审判", "你参与的争议已重新进入新一轮审判",
					LINK_DISPUTES, disputePayload(payload));
			case "DisputeAppealed" -> new Template(NotificationCategory.DISPUTE, "争议已申诉", "你参与的争议已提出申诉", LINK_DISPUTES,
					disputePayload(payload));
			case "AdjudicationEscalated" -> new Template(NotificationCategory.DISPUTE, "争议已升级客服", "你参与的争议已升级至客服终审",
					LINK_DISPUTES, disputePayload(payload));
			case "DisputeDecided" -> new Template(NotificationCategory.DISPUTE, "争议已判决", "你参与的争议已作出判决，点击查看结果",
					LINK_DISPUTES, disputePayload(payload));
			case "DisputeFinalized" -> new Template(NotificationCategory.DISPUTE, "争议已终裁", "你参与的争议已出终裁结果",
					LINK_DISPUTES, disputePayload(payload));
			// 任务书 #31 / ADR-D15 D7：审判官投票奖励（DISPUTE 类，收件人=judgeAccountId payload 直读）。
			case "JudgeVoteRewarded" -> new Template(NotificationCategory.DISPUTE, "审判奖励已到账",
					"你参与的一轮审判已终局，投票奖励积分已发放到你的账户", LINK_DISPUTES, judgeRewardPayload(payload));
			// ADR-D18：审判官现金佣金（WALLET 类，入推荐官钱包可提现；收件人=judgeAccountId payload 直读）。
			case "JudgeVoteCommissionRewarded" -> new Template(NotificationCategory.WALLET, "审判现金佣金已到账",
					"你参与的一轮审判已终局，投票现金佣金已发放到你的钱包", LINK_WALLET, judgeCommissionPayload(payload));
			// ---------- finance：钱包与资金 ----------
			case "FundsReserved" -> new Template(NotificationCategory.WALLET, "任务报酬已托管", "该笔履约的报酬已进入托管预留", LINK_WALLET,
					walletPayload(payload));
			case "FundsCaptured" ->
				new Template(NotificationCategory.WALLET, "佣金已到账", "任务佣金已入账到你的钱包", LINK_WALLET, walletPayload(payload));
			case "FundsReleased" -> new Template(NotificationCategory.WALLET, "托管资金已释放", "该笔托管资金已释放，不再计入本次履约",
					LINK_WALLET, walletPayload(payload));
			case "FundsReversed" -> new Template(NotificationCategory.WALLET, "结算资金已冲正", "该笔履约的资金已被冲正退回", LINK_WALLET,
					walletPayload(payload));
			case "AccountCredited" ->
				new Template(NotificationCategory.WALLET, "账户已充值", "你的组织账户已完成充值", LINK_WALLET, walletPayload(payload));
			// finance：霸王餐押金（ADR-D12，方向与商家出资 escrow 相反）。
			case "FreebieReserved" -> new Template(NotificationCategory.WALLET, "押金已预付托管",
					"你的霸王餐押金已从钱包预付进入平台托管，完成任务达标后全额返还", LINK_WALLET, walletPayload(payload));
			case "FreebieRefunded" -> new Template(NotificationCategory.WALLET, "押金已返还", "你的霸王餐押金已全额返还到钱包", LINK_WALLET,
					walletPayload(payload));
			case "FreebieCompensated" -> new Template(NotificationCategory.ENGAGEMENT, "霸王餐押金已补偿商家",
					"该履约未达标终局，推荐官预付的押金已补偿给商家", LINK_ENGAGEMENTS, walletPayload(payload));
			// ---------- intelligence：组织 AI 预算阈值告警（任务书 #37 登记项）----------
			// 收件人=组织 owner/admin（resolver 查本地 membership）；WALLET 类走邮件高价值子集。
			case "AiOrgBudgetThresholdCrossed" -> {
				boolean exceeded = "exceeded".equals(stringField(payload, "level"));
				boolean monthly = "monthly".equals(stringField(payload, "window"));
				boolean cents = "cents".equals(stringField(payload, "unit"));
				String dimension = (monthly ? "本月" : "今日") + (cents ? "消费金额" : "调用量");
				String title = exceeded ? "组织 AI 预算已超限" : "组织 AI 预算接近上限";
				String body = exceeded
						? "组织 AI " + dimension + "已超过设定的上限，相关 AI 能力可能已被硬停，请调整预算或检查用量"
						: "组织 AI " + dimension + "已达到预警阈值，请关注组织用量";
				yield new Template(NotificationCategory.WALLET, title, body, LINK_PERMISSION,
						budgetAlertPayload(payload));
			}
			// ---------- intelligence：个人 AI 预算阈值告警（GL-P3-AI-001 登记项）----------
			// 收件人=用户本人（resolver 按 payload.accountId）。
			case "AiPersonalBudgetThresholdCrossed" -> {
				boolean exceeded = "exceeded".equals(stringField(payload, "level"));
				boolean monthly = "monthly".equals(stringField(payload, "window"));
				boolean cents = "cents".equals(stringField(payload, "unit"));
				String dimension = (monthly ? "本月" : "今日") + (cents ? "消费金额" : "调用量");
				String title = exceeded ? "个人 AI 预算已超限" : "个人 AI 预算接近上限";
				String body = exceeded
						? "你的独立创作 AI " + dimension + "已超过自己设定的上限，相关 AI 能力可能已被硬停，可调整预算或检查用量"
						: "你的独立创作 AI " + dimension + "已达到你设定的预警阈值，请关注用量";
				yield new Template(NotificationCategory.WALLET, title, body, LINK_PERMISSION,
						budgetAlertPayload(payload));
			}
			default -> null;
		};
	}

	private static Map<String, Object> taskPayload(JsonNode payload) {
		Map<String, Object> map = new LinkedHashMap<>();
		putIfText(map, payload, "taskId");
		putIfText(map, payload, "invitationId");
		putIfText(map, payload, "applicationId");
		putIfText(map, payload, "submissionId");
		putIfText(map, payload, "status");
		putIfText(map, payload, "reason");
		return map;
	}

	private static Map<String, Object> disputePayload(JsonNode payload) {
		Map<String, Object> map = new LinkedHashMap<>();
		putIfText(map, payload, "disputeId");
		putIfText(map, payload, "engagementRef");
		putIfText(map, payload, "applicationId");
		putIfText(map, payload, "submissionId");
		putIfText(map, payload, "status");
		putIfText(map, payload, "decision");
		putIfText(map, payload, "finalDecision");
		return map;
	}

	/** 审判现金佣金 payload（ADR-D18）：争议定位 + 分数；不泄露其他审判官账号。 */
	private static Map<String, Object> judgeCommissionPayload(JsonNode payload) {
		Map<String, Object> map = new LinkedHashMap<>();
		putIfText(map, payload, "disputeId");
		putIfNumber(map, payload, "round");
		putIfNumber(map, payload, "amountCents");
		return map;
	}

	/** 审判奖励 payload：争议定位 + 积分数；不泄露其他审判官账号（D7）。 */
	private static Map<String, Object> judgeRewardPayload(JsonNode payload) {
		Map<String, Object> map = new LinkedHashMap<>();
		putIfText(map, payload, "disputeId");
		putIfNumber(map, payload, "round");
		putIfNumber(map, payload, "credits");
		return map;
	}

	private static Map<String, Object> walletPayload(JsonNode payload) {
		Map<String, Object> map = new LinkedHashMap<>();
		putIfText(map, payload, "engagementRef");
		putIfNumber(map, payload, "payoutCents");
		putIfNumber(map, payload, "amountCents");
		return map;
	}

	private static Map<String, Object> orgPayload(JsonNode payload) {
		return singleKeyPayload(payload, "organizationId");
	}

	/** 预算阈值告警 payload：主体（组织/个人） + 规则维度 + 等级 + 用量/上限（前端管理区渲染）。 */
	private static Map<String, Object> budgetAlertPayload(JsonNode payload) {
		Map<String, Object> map = new LinkedHashMap<>();
		putIfText(map, payload, "organizationId");
		putIfText(map, payload, "accountId");
		putIfText(map, payload, "ruleKey");
		putIfText(map, payload, "level");
		putIfText(map, payload, "window");
		putIfText(map, payload, "unit");
		putIfText(map, payload, "periodKey");
		putIfNumber(map, payload, "usage");
		putIfNumber(map, payload, "limit");
		return map;
	}

	private static Map<String, Object> grantedPayload(JsonNode payload) {
		Map<String, Object> map = new LinkedHashMap<>();
		putIfText(map, payload, "organizationId");
		putIfText(map, payload, "role");
		return map;
	}

	/** 只取一个文本字段进 payload（组织视图渲染用），缺字段则空 map。 */
	private static Map<String, Object> singleKeyPayload(JsonNode payload, String key) {
		Map<String, Object> map = new LinkedHashMap<>();
		putIfText(map, payload, key);
		return map;
	}

	private static void putIfNumber(Map<String, Object> map, JsonNode payload, String key) {
		JsonNode node = payload.get(key);
		if (node != null && node.isNumber()) {
			map.put(key, node.asLong());
		}
	}

	private static void putIfText(Map<String, Object> map, JsonNode payload, String key) {
		JsonNode node = payload.get(key);
		if (node != null && node.isTextual() && !node.asText().isBlank()) {
			map.put(key, node.asText());
		}
	}

	/** 读一个文本字段为纯 String（缺失/非文本 → null），供文案分支判定用，不写入通知 payload。 */
	private static String stringField(JsonNode payload, String key) {
		JsonNode node = payload.get(key);
		return (node == null || !node.isTextual()) ? null : node.asText();
	}

	/** 静态文案模板（不含收件人——收件人由 resolver 异步解析后与模板合成 {@link NotificationSpec}）。 */
	public record Template(NotificationCategory category, String title, String body, String linkPath,
			Map<String, Object> payload) {
	}
}
