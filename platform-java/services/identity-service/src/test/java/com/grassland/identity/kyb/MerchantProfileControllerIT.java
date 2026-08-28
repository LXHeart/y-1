package com.grassland.identity.kyb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import com.grassland.identity.IdentityItSupport;
import com.grassland.messaging.outbox.OutboxRepository;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Mono;

/**
 * 商家 KYB 资料与提交闭环。GL-P3-MERCHANT-001。
 *
 * <p>
 * {@code 9def15e} 留下的 KYB 脚手架能编译但从未被守卫/接线/测试。本类锁住其中的高危回归：
 * <ul>
 * <li>已通过审核的资料不能被一次 POST 静默打回 draft；</li>
 * <li>提交前必填字段与证件材料齐备校验；</li>
 * <li>提交真的写出 {@code kyb_verification_request} 行（此前 admin 队列恒空）；</li>
 * <li>身份证号密文入库、响应体只回末 4 位。</li>
 * </ul>
 */
class MerchantProfileControllerIT extends IdentityItSupport {

	@MockitoSpyBean
	private OutboxRepository outbox;

	@DynamicPropertySource
	static void kek(DynamicPropertyRegistry r) {
		// 敏感字段加密 fail-closed，测试里必须显式给 KEK 才能走通含身份证号的路径。
		r.add("crypto.kek.encoded", () -> Base64.getEncoder().encodeToString(new byte[32]));
	}

	private String draftBody(String usccSuffix) {
		return """
				{"legalName":"草场测试商贸有限公司",
				 "unifiedSocialCreditCode":"91310000MA1K%s",
				 "businessType":"company",
				 "legalPersonName":"张三",
				 "legalPersonIdNumber":"310101199001011234",
				 "registeredCapitalCents":100000000,
				 "establishmentDate":"2020-06-01",
				 "businessAddress":{"province":"上海市","city":"上海市","district":"黄浦区",
				                    "address":"南京东路 1 号"},
				 "contactPhone":"13800138000",
				 "contactEmail":"kyb@example.com"}
				""".formatted(usccSuffix);
	}

	private String draftBodyWithIndustry(String usccSuffix, String industry) {
		return draftBody(usccSuffix).replace("\"businessType\":\"company\",",
				"\"businessType\":\"company\",\n \"industry\":\"" + industry + "\",");
	}

	private void postDraft(String orgId, String cookie, String body, int expectedStatus) {
		client().post().uri("/api/organizations/" + orgId + "/merchant-profile").contentType(MediaType.APPLICATION_JSON)
				.header("Cookie", "y1.sid=" + cookie).bodyValue(body).exchange().expectStatus()
				.isEqualTo(expectedStatus);
	}

	private void postDraftInvalid(String orgId, String cookie, String body, String expectedError) {
		client().post().uri("/api/organizations/" + orgId + "/merchant-profile")
				.contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie).bodyValue(body)
				.exchange().expectStatus().isBadRequest().expectBody()
				.jsonPath("$.error").value(message -> assertThat((String) message).contains(expectedError));
	}

	private void uploadAllDocuments(String orgId, String cookie) {
		for (String type : new String[]{"business_license", "legal_person_id_front", "legal_person_id_back"}) {
			client().post().uri("/api/organizations/" + orgId + "/merchant-attachments")
					.contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
					.bodyValue("{\"attachmentType\":\"" + type + "\",\"mediaReferenceId\":\"" + UUID.randomUUID()
							+ "\",\"mimeType\":\"image/jpeg\",\"sizeBytes\":102400}")
					.exchange().expectStatus().isEqualTo(201);
		}
	}

	@Test
	@DisplayName("未登录 401；非 ADMIN 成员 403")
	void authorization() {
		var owner = seedAccount("kyb-authz-owner-" + UUID.randomUUID() + "@example.com");
		String orgId = createOrg(owner.cookie(), "KYB Authz Org");

		client().post().uri("/api/organizations/" + orgId + "/merchant-profile").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(draftBody("0001")).exchange().expectStatus().isUnauthorized();

		var outsider = seedAccount("kyb-authz-out-" + UUID.randomUUID() + "@example.com");
		postDraft(orgId, outsider.cookie(), draftBody("0002"), 403);
	}

	@Test
	@DisplayName("草稿 upsert：身份证号密文入库，响应只回末 4 位掩码")
	void draftEncryptsIdNumber() {
		var owner = seedAccount("kyb-draft-" + UUID.randomUUID() + "@example.com");
		String orgId = createOrg(owner.cookie(), "KYB Draft Org");

		postDraft(orgId, owner.cookie(), draftBody("0101"), 200);

		client().get().uri("/api/organizations/" + orgId + "/merchant-profile")
				.header("Cookie", "y1.sid=" + owner.cookie()).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.legalPersonIdNumberMasked").isEqualTo("****1234").jsonPath("$.data.status")
				.isEqualTo("draft")
				// 明文字段名不该出现在响应里。
				.jsonPath("$.data.legalPersonIdNumber").doesNotExist();

		String stored = db
				.sql("SELECT legal_person_id_number FROM merchant_profile WHERE organization_id = CAST(:org AS uuid)")
				.bind("org", orgId).map(row -> row.get(0, String.class)).one().block();
		assertThat(stored).isNotNull().doesNotContain("310101199001011234");
	}

	@Test
	@DisplayName("POST 校验电话、邮箱和身份证；支持座机与 15 位身份证")
	void postValidatesContactFieldsBeforeInsert() {
		var owner = seedAccount("kyb-fields-post-" + UUID.randomUUID() + "@example.com");
		String orgId = createOrg(owner.cookie(), "KYB Fields POST Org");
		String valid = draftBody("0104");

		postDraftInvalid(orgId, owner.cookie(), valid.replace("13800138000", "12800138000"), "联系电话");
		postDraftInvalid(orgId, owner.cookie(), valid.replace("kyb@example.com", "kyb@example"), "联系邮箱");
		postDraftInvalid(orgId, owner.cookie(), valid.replace("310101199001011234", "110105194912310021"),
				"身份证号");
		postDraftInvalid(orgId, owner.cookie(), valid.replace("310101199001011234", "110105199902300023"),
				"身份证号");
		postDraftInvalid(orgId, owner.cookie(), valid.replace("310101199001011234", "110000194912310022"),
				"身份证号");
		postDraftInvalid(orgId, owner.cookie(), valid.replace("310101199001011234", "119999194912310021"),
				"身份证号");

		Long profiles = db
				.sql("SELECT count(*) FROM merchant_profile WHERE organization_id = CAST(:org AS uuid)")
				.bind("org", orgId).map(row -> row.get(0, Long.class)).one().block();
		assertThat(profiles).isZero();

		client().post().uri("/api/organizations/" + orgId + "/merchant-profile")
				.contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
				.bodyValue(valid.replace("13800138000", "010-12345678")
						.replace("310101199001011234", "110105491231002")
						.replace("kyb@example.com", "name+tag@sub.example.cn"))
				.exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.contactPhone").isEqualTo("010-12345678")
				.jsonPath("$.data.contactEmail").isEqualTo("name+tag@sub.example.cn")
				.jsonPath("$.data.legalPersonIdNumberMasked").isEqualTo("****1002");
	}

	@Test
	@DisplayName("PUT 非法联系方式或身份证返回 400 且不修改原资料")
	void putRejectsInvalidContactFieldsWithoutMutation() {
		var owner = seedAccount("kyb-fields-put-" + UUID.randomUUID() + "@example.com");
		String orgId = createOrg(owner.cookie(), "KYB Fields PUT Org");
		postDraft(orgId, owner.cookie(), draftBody("0105"), 200);

		String invalidPhone = draftBody("0105").replace("13800138000", "010--12345678");
		client().put().uri("/api/organizations/" + orgId + "/merchant-profile")
				.contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
				.bodyValue(invalidPhone).exchange().expectStatus().isBadRequest().expectBody()
				.jsonPath("$.error").value(message -> assertThat((String) message).contains("联系电话"));

		String invalidEmail = draftBody("0105").replace("kyb@example.com", "user..name@example.com");
		client().put().uri("/api/organizations/" + orgId + "/merchant-profile")
				.contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
				.bodyValue(invalidEmail).exchange().expectStatus().isBadRequest();

		String invalidId = draftBody("0105").replace("310101199001011234", "110000194912310022");
		client().put().uri("/api/organizations/" + orgId + "/merchant-profile")
				.contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
				.bodyValue(invalidId).exchange().expectStatus().isBadRequest();
		String unknownArea = draftBody("0105").replace("310101199001011234", "119999194912310021");
		client().put().uri("/api/organizations/" + orgId + "/merchant-profile")
				.contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
				.bodyValue(unknownArea).exchange().expectStatus().isBadRequest();

		Map<String, Object> stored = db.sql("SELECT contact_phone, contact_email FROM merchant_profile "
				+ "WHERE organization_id = CAST(:org AS uuid)").bind("org", orgId).fetch().one().block();
		assertThat(stored).containsEntry("contact_phone", "13800138000")
				.containsEntry("contact_email", "kyb@example.com");

		String historicalArea = draftBody("0105").replace("310101199001011234", "110103194912310027");
		client().put().uri("/api/organizations/" + orgId + "/merchant-profile")
				.contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
				.bodyValue(historicalArea).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.legalPersonIdNumberMasked").isEqualTo("****0027");
	}

	@Test
	@DisplayName("行业写入 organization，businessType 保持企业类型，POST/PUT/GET 响应均回填")
	void industryUsesOrganizationContractWithoutPollutingBusinessType() {
		var owner = seedAccount("kyb-industry-" + UUID.randomUUID() + "@example.com");
		String orgId = createOrg(owner.cookie(), "KYB Industry Org");

		client().post().uri("/api/organizations/" + orgId + "/merchant-profile")
				.contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
				.bodyValue(draftBodyWithIndustry("0102", "retail")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.industry").isEqualTo("retail")
				.jsonPath("$.data.businessType").isEqualTo("company");

		String storedIndustry = db.sql("SELECT industry FROM organization WHERE id = CAST(:org AS uuid)")
				.bind("org", orgId).map(row -> row.get(0, String.class)).one().block();
		String storedBusinessType = db
				.sql("SELECT business_type FROM merchant_profile WHERE organization_id = CAST(:org AS uuid)")
				.bind("org", orgId).map(row -> row.get(0, String.class)).one().block();
		assertThat(storedIndustry).isEqualTo("retail");
		assertThat(storedBusinessType).isEqualTo("company");

		client().put().uri("/api/organizations/" + orgId + "/merchant-profile")
				.contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
				.bodyValue(draftBodyWithIndustry("0102", "education")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.industry").isEqualTo("education")
				.jsonPath("$.data.businessType").isEqualTo("company");

		client().get().uri("/api/organizations/" + orgId + "/merchant-profile")
				.header("Cookie", "y1.sid=" + owner.cookie()).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.industry").isEqualTo("education")
				.jsonPath("$.data.businessType").isEqualTo("company");

		client().put().uri("/api/organizations/" + orgId + "/merchant-profile")
				.contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
				.bodyValue(draftBodyWithIndustry("0102", "other")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.industry").isEqualTo("other");
	}

	@Test
	@DisplayName("旧客户端省略行业时保留原值，存量禁止/未知行业可显式原值保存")
	void omittedAndLegacyIndustryValuesArePreserved() {
		var owner = seedAccount("kyb-industry-legacy-" + UUID.randomUUID() + "@example.com");
		String orgId = createOrg(owner.cookie(), "KYB Legacy Industry Org");
		db.sql("UPDATE organization SET industry = 'gambling' WHERE id = CAST(:org AS uuid)")
				.bind("org", orgId).then().block();

		postDraft(orgId, owner.cookie(), draftBody("0103"), 200);
		client().put().uri("/api/organizations/" + orgId + "/merchant-profile")
				.contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
				.bodyValue(draftBodyWithIndustry("0103", "gambling")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.industry").isEqualTo("gambling");

		db.sql("UPDATE organization SET industry = 'legacy_industry' WHERE id = CAST(:org AS uuid)")
				.bind("org", orgId).then().block();
		client().put().uri("/api/organizations/" + orgId + "/merchant-profile")
				.contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
				.bodyValue(draftBodyWithIndustry("0103", "legacy_industry")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.industry").isEqualTo("legacy_industry");
	}

	@Test
	@DisplayName("新设禁止行业或非法行业 → 400，组织和商家资料均不写入")
	void rejectsNewProhibitedAndUnknownIndustries() {
		String[] industries = {"gambling", "adult", "not_an_industry"};
		for (int index = 0; index < industries.length; index++) {
			var owner = seedAccount("kyb-industry-invalid-" + index + "-" + UUID.randomUUID() + "@example.com");
			String orgId = createOrg(owner.cookie(), "KYB Invalid Industry " + index);

			postDraft(orgId, owner.cookie(), draftBodyWithIndustry("011" + index, industries[index]), 400);

			String storedIndustry = db.sql("SELECT industry FROM organization WHERE id = CAST(:org AS uuid)")
					.bind("org", orgId).map(row -> row.get(0, String.class)).one().block();
			Long profiles = db
					.sql("SELECT count(*) FROM merchant_profile WHERE organization_id = CAST(:org AS uuid)")
					.bind("org", orgId).map(row -> row.get(0, Long.class)).one().block();
			assertThat(storedIndustry).isEqualTo("other");
			assertThat(profiles).isZero();
		}
	}

	@Test
	@DisplayName("更新身份证号为空或省略时保留原密文；支持 400 服务号码")
	void updateWithBlankOrMissingIdNumberPreservesCiphertext() {
		var owner = seedAccount("kyb-preserve-id-" + UUID.randomUUID() + "@example.com");
		String orgId = createOrg(owner.cookie(), "KYB Preserve ID Org");
		postDraft(orgId, owner.cookie(), draftBody("0111"), 200);
		String before = db
				.sql("SELECT legal_person_id_number FROM merchant_profile "
						+ "WHERE organization_id = CAST(:org AS uuid)")
					.bind("org", orgId).map(row -> row.get(0, String.class)).one().block();
		String bodyWithBlankId = draftBody("0112")
				.replace("\"legalPersonIdNumber\":\"310101199001011234\"", "\"legalPersonIdNumber\":\"   \"")
				.replace("13800138000", "400-123-4567");

		client().put().uri("/api/organizations/" + orgId + "/merchant-profile").contentType(MediaType.APPLICATION_JSON)
				.header("Cookie", "y1.sid=" + owner.cookie()).bodyValue(bodyWithBlankId).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.legalPersonIdNumberMasked").isEqualTo("****1234")
				.jsonPath("$.data.contactPhone").isEqualTo("400-123-4567");

		String bodyWithoutId = draftBody("0112").replace("\"legalPersonIdNumber\":\"310101199001011234\",", "");

		client().put().uri("/api/organizations/" + orgId + "/merchant-profile").contentType(MediaType.APPLICATION_JSON)
				.header("Cookie", "y1.sid=" + owner.cookie()).bodyValue(bodyWithoutId).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.legalPersonIdNumberMasked").isEqualTo("****1234");

		String after = db
				.sql("SELECT legal_person_id_number FROM merchant_profile "
						+ "WHERE organization_id = CAST(:org AS uuid)")
				.bind("org", orgId).map(row -> row.get(0, String.class)).one().block();
		assertThat(after).isEqualTo(before);
	}

	@Test
	@DisplayName("资料不完整时提交 → 400 且列出缺失字段")
	void submitRejectsIncompleteProfile() {
		var owner = seedAccount("kyb-incomplete-" + UUID.randomUUID() + "@example.com");
		String orgId = createOrg(owner.cookie(), "KYB Incomplete Org");

		// 只填法定名称，其余留空——此前 Map.of 装可空列直接 NPE→500。
		postDraft(orgId, owner.cookie(), "{\"legalName\":\"仅名称\"}", 200);

		client().post().uri("/api/organizations/" + orgId + "/merchant-profile/submit")
				.header("Cookie", "y1.sid=" + owner.cookie()).exchange().expectStatus().isBadRequest().expectBody()
				.jsonPath("$.error").value(msg -> assertThat((String) msg).contains("统一社会信用代码").contains("法人姓名")
						.contains("法人身份证号").contains("经营地址"));
	}

	@Test
	@DisplayName("材料不齐时提交 → 400 且列出缺失材料")
	void submitRejectsMissingDocuments() {
		var owner = seedAccount("kyb-nodocs-" + UUID.randomUUID() + "@example.com");
		String orgId = createOrg(owner.cookie(), "KYB NoDocs Org");
		postDraft(orgId, owner.cookie(), draftBody("0201"), 200);

		client().post().uri("/api/organizations/" + orgId + "/merchant-profile/submit")
				.header("Cookie", "y1.sid=" + owner.cookie()).exchange().expectStatus().isBadRequest().expectBody()
				.jsonPath("$.error").value(msg -> assertThat((String) msg).contains("营业执照"));
	}

	@Test
	@DisplayName("资料齐全提交 → pending + 写出审核队列行 + outbox 事件")
	void submitEnqueuesReviewRequest() {
		var owner = seedAccount("kyb-submit-" + UUID.randomUUID() + "@example.com");
		String orgId = createOrg(owner.cookie(), "KYB Submit Org");
		postDraft(orgId, owner.cookie(), draftBody("0301"), 200);
		uploadAllDocuments(orgId, owner.cookie());

		client().post().uri("/api/organizations/" + orgId + "/merchant-profile/submit")
				.header("Cookie", "y1.sid=" + owner.cookie()).exchange().expectStatus().isEqualTo(201).expectBody()
				.jsonPath("$.data.status").isEqualTo("pending");

		// 审核队列行必须存在——此前 create 全仓无调用方，admin 队列恒空、approve 不可达。
		Long requests = db.sql("SELECT count(*) FROM kyb_verification_request "
				+ "WHERE organization_id = CAST(:org AS uuid) AND verification_type = 'merchant_profile' AND status = 'pending'")
				.bind("org", orgId).map(row -> row.get(0, Long.class)).one().block();
		assertThat(requests).isEqualTo(1L);

		// 材料快照非空：审核人看到的是提交那一刻的附件集合。
		String materials = db
				.sql("SELECT materials::text FROM kyb_verification_request WHERE organization_id = CAST(:org AS uuid)")
				.bind("org", orgId).map(row -> row.get(0, String.class)).one().block();
		assertThat(materials).isNotNull().startsWith("[");

		Long events = db
				.sql("SELECT count(*) FROM outbox "
						+ "WHERE aggregate_id = :org AND event_type = 'MerchantProfileSubmitted'")
				.bind("org", orgId).map(row -> row.get(0, Long.class)).one().block();
		assertThat(events).isEqualTo(1L);

		Long reviewRetentions = db
				.sql("SELECT count(*) FROM kyb_media_retention_sync sync "
						+ "JOIN kyb_verification_request request ON request.id=sync.reference_id "
						+ "WHERE request.organization_id=CAST(:org AS uuid) "
						+ "AND sync.reference_type='review_request' AND sync.desired_state='live'")
				.bind("org", orgId).map(row -> row.get(0, Long.class)).one().block();
		assertThat(reviewRetentions).isEqualTo(3L);

		// 重复提交：已在审核中，既不可编辑也不该堆出第二条待审。
		client().post().uri("/api/organizations/" + orgId + "/merchant-profile/submit")
				.header("Cookie", "y1.sid=" + owner.cookie()).exchange().expectStatus().isEqualTo(409);
		postDraft(orgId, owner.cookie(), draftBody("0302"), 409);
	}

	@Test
	@DisplayName("提交事件写入失败时回滚审核状态并补偿释放 request retention")
	void outboxFailureRollsBackSubmissionAndReleasesReviewRetention() {
		var owner = seedAccount("kyb-submit-outbox-" + UUID.randomUUID() + "@example.com");
		String orgId = createOrg(owner.cookie(), "KYB Submit Outbox Org");
		postDraft(orgId, owner.cookie(), draftBody("0311"), 200);
		uploadAllDocuments(orgId, owner.cookie());
		doReturn(Mono.error(new RuntimeException("injected outbox failure"))).when(outbox)
				.append(argThat(event -> "MerchantProfileSubmitted".equals(event.eventType())));

		client().post().uri("/api/organizations/" + orgId + "/merchant-profile/submit")
				.header("Cookie", "y1.sid=" + owner.cookie()).exchange().expectStatus().is5xxServerError();

		String status = db.sql("SELECT status FROM merchant_profile WHERE organization_id = CAST(:org AS uuid)")
				.bind("org", orgId).map(row -> row.get(0, String.class)).one().block();
		assertThat(status).isEqualTo("draft");
		Long requests = db
				.sql("SELECT count(*) FROM kyb_verification_request " + "WHERE organization_id = CAST(:org AS uuid)")
				.bind("org", orgId).map(row -> row.get(0, Long.class)).one().block();
		assertThat(requests).isZero();
		verify(kybMediaClient, atLeast(3)).release(any(), eq(orgId), any());
	}

	@Test
	@DisplayName("回归：POST 不能把已通过审核的资料打回 draft")
	void postDoesNotRevertApprovedStatus() {
		var owner = seedAccount("kyb-approved-" + UUID.randomUUID() + "@example.com");
		String orgId = createOrg(owner.cookie(), "KYB Approved Org");
		postDraft(orgId, owner.cookie(), draftBody("0401"), 200);
		db.sql("UPDATE merchant_profile SET status = 'approved' WHERE organization_id = CAST(:org AS uuid)")
				.bind("org", orgId).then().block();

		// 此前 upsert 无条件 status = EXCLUDED.status，一次 POST 就把 approved 静默改回 draft。
		postDraft(orgId, owner.cookie(), draftBody("0402"), 409);

		String status = db.sql("SELECT status FROM merchant_profile WHERE organization_id = CAST(:org AS uuid)")
				.bind("org", orgId).map(row -> row.get(0, String.class)).one().block();
		assertThat(status).isEqualTo("approved");
	}

	@Test
	@DisplayName("统一社会信用代码重复 → 409 而非 500")
	void duplicateUsccIsConflict() {
		var first = seedAccount("kyb-dup-a-" + UUID.randomUUID() + "@example.com");
		String firstOrg = createOrg(first.cookie(), "KYB Dup A");
		postDraft(firstOrg, first.cookie(), draftBody("0501"), 200);

		var second = seedAccount("kyb-dup-b-" + UUID.randomUUID() + "@example.com");
		String secondOrg = createOrg(second.cookie(), "KYB Dup B");
		postDraft(secondOrg, second.cookie(), draftBodyWithIndustry("0501", "retail"), 409);

		// 行业与 profile 在同一事务中；profile 唯一冲突时 organization 更新也必须回滚。
		String industry = db.sql("SELECT industry FROM organization WHERE id = CAST(:org AS uuid)")
				.bind("org", secondOrg).map(row -> row.get(0, String.class)).one().block();
		assertThat(industry).isEqualTo("other");
	}

	@Test
	@DisplayName("成立日期格式非法 → 400 而非 500")
	void malformedDateIsBadRequest() {
		var owner = seedAccount("kyb-baddate-" + UUID.randomUUID() + "@example.com");
		String orgId = createOrg(owner.cookie(), "KYB BadDate Org");

		postDraft(orgId, owner.cookie(), "{\"legalName\":\"日期测试\",\"establishmentDate\":\"2020/06/01\"}", 400);
	}
}
