package com.grassland.identity.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 任务书 #103 C103-13：履约事件通知纯合约（TC103-13-01/02/05）。
 *
 * <p>
 * 用生产者实际字段形状的 payload 样本跑「模板 → 收件人纯策略 → 邮件 → 深链 payload」： §6.4 矩阵每一行不再
 * IGNORED；收件人按 M/R/O 规则与去重；长文案/证据不进通知； 旧 payload（缺
 * organizationId/operatorAccountId 等新键）兼容不炸。 真实 Kafka/DB 链路（含 M 兜底 SQL）在
 * {@code EngagementNotificationCrossKafkaIT}（C103-14）验证。
 */
class EngagementNotificationContractTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	@DisplayName("TC103-13-01 五个直接复现的 IGNORED 事件（R05）全部产生模板+邮件")
	void fiveReproducedEventsNowProduceNotifications() {
		JsonNode deadline = payload("""
				{"taskId":"t1","applicationId":"a1","recommenderAccountId":"R","taskOwnerId":"M",
				 "deliveryDeadlineAt":"2026-09-20T00:00:00Z","remedyDeadlineAt":null}""");
		assertThat(NotificationTemplates.template("DeliveryDeadlineExpiring", deadline)).isNotNull();
		assertThat(MailTemplates.mailTemplate("DeliveryDeadlineExpiring", deadline)).isNotNull();

		JsonNode extension = payload("""
				{"taskId":"t1","applicationId":"a1","recommenderAccountId":"R","taskOwnerId":"M",
				 "organizationId":"11111111-1111-4111-8111-111111111111","operatorAccountId":"R",
				 "extensionId":"e1","days":3,"reason":"出差"}""");
		var extensionTemplate = NotificationTemplates.template("DeliveryExtensionRequested", extension);
		assertThat(extensionTemplate).isNotNull();
		// E02：reason/长文案不进通知 payload——白名单只有定位键与短事实
		assertThat(extensionTemplate.payload()).doesNotContainKeys("reason", "contentUrl", "settlement");

		JsonNode reviewExpiring = payload("""
				{"taskId":"t1","applicationId":"a1","recommenderAccountId":"R","taskOwnerId":"M",
				 "submissionId":"s1","reason":"draft_review_timeout"}""");
		assertThat(NotificationTemplates.template("DraftReviewExpiring", reviewExpiring)).isNotNull();

		JsonNode exitRequested = payload("""
				{"taskId":"t1","applicationId":"a1","recommenderAccountId":"R","taskOwnerId":"M",
				 "initiatedRole":"recommender","exitRequestId":"x1",
				 "respondDeadlineAt":"2026-09-18T00:00:00Z","status":"pending"}""");
		assertThat(NotificationTemplates.template("EngagementExitRequested", exitRequested)).isNotNull();

		JsonNode defaultClaimed = payload("""
				{"taskId":"t1","applicationId":"a1","recommenderAccountId":"R","taskOwnerId":"M",
				 "benefitId":"b1","benefitStatus":"default_claimed"}""");
		assertThat(NotificationTemplates.template("BenefitDefaultClaimed", defaultClaimed)).isNotNull();
	}

	@Test
	@DisplayName("矩阵全部 21 事件族产生模板（无 IGNORED 漏洞），focus 只取白名单值")
	void wholeMatrixProducesTemplatesWithWhitelistedFocus() {
		List<String> matrixEvents = List.of("DeliveryDeadlineExpiring", "DeliveryExtensionRequested",
				"DeliveryExtensionApproved", "DeliveryExtensionRejected", "DeliveryTimeoutTerminated", "DraftSubmitted",
				"DraftReviewExpiring", "EngagementExitRequested", "EngagementExitRejected", "EngagementExitCancelled",
				"EngagementExitExpired", "ApplicationExitedNoFault", "EngagementExitedNegotiated", "BenefitBooked",
				"BenefitFulfilled", "BenefitFulfillmentConfirmed", "BenefitDefaultClaimed", "BenefitDefaultDenied",
				"BenefitDefaultEstablished", "BenefitCancelled", "MilestoneConfirmed");
		JsonNode base = payload("""
				{"taskId":"t1","applicationId":"a1","recommenderAccountId":"R","taskOwnerId":"M",
				 "extensionId":"e1","exitRequestId":"x1","exitOperationId":"o1","submissionId":"s1",
				 "benefitId":"b1","milestoneId":"m1","days":3,
				 "deliveryDeadlineAt":"2026-09-20T00:00:00Z","respondDeadlineAt":"2026-09-18T00:00:00Z",
				 "reviewDueAt":"2026-09-17T00:00:00Z","responseDueAt":"2026-09-16T00:00:00Z",
				 "exitedAt":"2026-09-15T00:00:00Z","reason":"很长的理由文本不应进入通知"}""");
		for (String eventType : matrixEvents) {
			NotificationTemplates.Template template = NotificationTemplates.template(eventType, base);
			assertThat(template).as("%s must produce a template", eventType).isNotNull();
			assertThat(template.linkPath()).isEqualTo("/me/engagements");
			assertThat(template.payload()).as("%s focus", eventType).containsEntry("focus", focusOf(eventType));
			// E02：白名单外字段（reason/长正文/结算明细/组织与操作者账号）不进通知
			assertThat(template.payload()).doesNotContainKeys("reason", "contentUrl", "settlement", "initiatedRole",
					"organizationId", "operatorAccountId", "recommenderAccountId", "taskOwnerId");
		}
	}

	@Test
	@DisplayName("TC103-13-01 坏载荷：缺必要 ID 的模板退化但不拼任意定位")
	void malformedPayloadsDegradeSafely() {
		JsonNode empty = payload("{}");
		var template = NotificationTemplates.template("DeliveryDeadlineExpiring", empty);
		assertThat(template).isNotNull();
		// taskId/applicationId 缺失 → payload 只剩 focus，不出现 undefined/拼接值
		assertThat(template.payload()).containsExactly(java.util.Map.entry("focus", "delivery"));

		JsonNode badTypes = payload("{\"taskId\":42,\"applicationId\":\"  \",\"deliveryDeadlineAt\":\"\"}");
		var degraded = NotificationTemplates.template("DeliveryDeadlineExpiring", badTypes);
		assertThat(degraded.payload()).containsExactly(java.util.Map.entry("focus", "delivery"));
	}

	@Test
	@DisplayName("TC103-13-02/E03/E14 纯收件人策略：R 行、M 行、M+R 行、三角行、O 排除与去重")
	void pureRecipientPolicyPerMatrix() {
		JsonNode base = payload(
				"{\"taskId\":\"t1\",\"applicationId\":\"a1\",\"recommenderAccountId\":\"R\",\"taskOwnerId\":\"M\"}");

		// R 行：只推荐官
		assertThat(NotificationRecipientResolver.engagementPolicy("DeliveryDeadlineExpiring", base).directRecipients())
				.containsExactly("R");
		// M 行：只商家归属人
		assertThat(
				NotificationRecipientResolver.engagementPolicy("DeliveryExtensionRequested", base).directRecipients())
				.containsExactly("M");
		// M+R 行：双侧（按 M、R 顺序去重）
		assertThat(NotificationRecipientResolver.engagementPolicy("MilestoneConfirmed", base).directRecipients())
				.containsExactly("M", "R");
		// 同账号双侧去重为一份
		JsonNode sameAccount = payload("{\"taskOwnerId\":\"X\",\"recommenderAccountId\":\"X\"}");
		assertThat(
				NotificationRecipientResolver.engagementPolicy("EngagementExitExpired", sameAccount).directRecipients())
				.containsExactly("X");
		// O 排除行：operatorAccountId 命中任一侧都被剔除
		JsonNode withOperator = payload(
				"{\"taskOwnerId\":\"M\",\"recommenderAccountId\":\"R\",\"operatorAccountId\":\"R\"}");
		assertThat(NotificationRecipientResolver.engagementPolicy("ApplicationExitedNoFault", withOperator)
				.directRecipients()).containsExactly("M");
		// 非 O 排除行同载荷不去除
		assertThat(
				NotificationRecipientResolver.engagementPolicy("MilestoneConfirmed", withOperator).directRecipients())
				.containsExactly("M", "R");
		// 三角行：recommender 发起 → 对方 = M；merchant 发起 → 对方 = R
		JsonNode initiatedByRecommender = payload(
				"{\"initiatedRole\":\"recommender\",\"taskOwnerId\":\"M\",\"recommenderAccountId\":\"R\"}");
		assertThat(NotificationRecipientResolver.engagementPolicy("EngagementExitRequested", initiatedByRecommender)
				.directRecipients()).containsExactly("M");
		JsonNode initiatedByMerchant = payload(
				"{\"initiatedRole\":\"merchant\",\"taskOwnerId\":\"M\",\"recommenderAccountId\":\"R\"}");
		assertThat(NotificationRecipientResolver.engagementPolicy("EngagementExitRequested", initiatedByMerchant)
				.directRecipients()).containsExactly("R");
	}

	@Test
	@DisplayName("TC103-13-03/E08 M 缺失兜底：organizationId 合法才标记兜底；非法/缺失不扩大收件人")
	void merchantFallbackOnlyWithValidOrganization() {
		JsonNode noOwner = payload(
				"{\"recommenderAccountId\":\"R\",\"organizationId\":\"11111111-1111-4111-8111-111111111111\"}");
		var fallback = NotificationRecipientResolver.engagementPolicy("DraftSubmitted", noOwner);
		assertThat(fallback.merchantFallbackNeeded()).isTrue();
		assertThat(fallback.directRecipients()).isEmpty(); // 兜底由 DB 层补 owner/admin

		JsonNode noOwnerNoOrg = payload("{\"recommenderAccountId\":\"R\"}");
		var noFallback = NotificationRecipientResolver.engagementPolicy("DraftSubmitted", noOwnerNoOrg);
		assertThat(noFallback.merchantFallbackNeeded()).isFalse();
		assertThat(noFallback.directRecipients()).isEmpty(); // 不可送达：不扩大到无关成员

		JsonNode badOrg = payload("{\"recommenderAccountId\":\"R\",\"organizationId\":\"not-a-uuid\"}");
		assertThat(NotificationRecipientResolver.engagementPolicy("DraftSubmitted", badOrg).merchantFallbackNeeded())
				.isFalse();
	}

	@Test
	@DisplayName("TC103-13-05/E19 旧 payload 兼容：缺新键不影响直读收件人与模板缺省")
	void legacyPayloadWithoutNewKeysStillResolves() {
		JsonNode legacy = payload("{\"taskId\":\"t1\",\"applicationId\":\"a1\",\"recommenderAccountId\":\"R\"}");
		// 旧字节无 organizationId/operatorAccountId：R 行照常解析；M 行不兜底不炸
		assertThat(NotificationRecipientResolver.engagementPolicy("BenefitFulfilled", legacy).directRecipients())
				.containsExactly("R");
		assertThat(NotificationRecipientResolver.engagementPolicy("BenefitBooked", legacy).merchantFallbackNeeded())
				.isFalse();
		var template = NotificationTemplates.template("BenefitFulfilled", legacy);
		assertThat(template.payload()).containsEntry("focus", "benefit").doesNotContainKey("benefitId");
	}

	// ---------- helpers ----------

	private JsonNode payload(String json) {
		try {
			return mapper.readTree(json);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	private String focusOf(String eventType) {
		return switch (eventType) {
			case "DeliveryDeadlineExpiring", "DeliveryTimeoutTerminated" -> "delivery";
			case "DeliveryExtensionRequested", "DeliveryExtensionApproved", "DeliveryExtensionRejected" -> "extension";
			case "DraftSubmitted", "DraftReviewExpiring" -> "draft-review";
			case "EngagementExitRequested", "EngagementExitRejected", "EngagementExitCancelled",
					"EngagementExitExpired", "ApplicationExitedNoFault", "EngagementExitedNegotiated" ->
				"exit";
			case "BenefitBooked", "BenefitFulfilled", "BenefitFulfillmentConfirmed", "BenefitDefaultClaimed",
					"BenefitDefaultDenied", "BenefitDefaultEstablished", "BenefitCancelled" ->
				"benefit";
			case "MilestoneConfirmed" -> "milestone";
			default -> throw new IllegalArgumentException(eventType);
		};
	}
}
