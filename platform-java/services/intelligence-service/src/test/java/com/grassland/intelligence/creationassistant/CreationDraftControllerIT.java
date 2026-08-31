package com.grassland.intelligence.creationassistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 创作草稿集成测试（草场 PRD §4.9.7 / Slice 15 Stage 1）。复用 {@link IntelligenceItSupport}
 * （testcontainers postgres + 真实断言签名）。草稿不依赖对象存储，CRUD 全可测。
 *
 * <p>
 * 锁定：创建（source 关联）、列表（仅自己）、详情（跨账号 404）、自动保存（落 version 快照 + 乐观锁
 * 409）、软删（删后不可见/不可下钻）、鉴权。
 */
@SuppressWarnings("unchecked")
class CreationDraftControllerIT extends IntelligenceItSupport {

	private String header() {
		return "X-Grassland-Identity";
	}

	@SuppressWarnings("unchecked")
	private String createDraft(String account, String sourceType, String title) {
		Map<String, Object> response = client().post().uri("/api/creation-drafts").header(header(), sign(account, null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("sourceType", sourceType, "title", title, "topic", "测试主题")).exchange().expectStatus()
				.isOk().expectBody(Map.class).returnResult().getResponseBody();
		return ((Map<String, Object>) response.get("data")).get("id").toString();
	}

	@Test
	void createDraftReturnsIdAndDefaults() {
		Map<String, Object> response = client().post().uri("/api/creation-drafts")
				.header(header(), sign("user-a", null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("sourceType", "independent", "title", "我的第一篇", "topic", "小红书爆款")).exchange()
				.expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();

		assertThat(response).containsEntry("success", true);
		Map<String, Object> data = (Map<String, Object>) response.get("data");
		assertThat(data).containsEntry("sourceType", "independent");
		assertThat(data).containsEntry("status", "draft");
		assertThat(data).containsEntry("version", 1);
		assertThat(data).containsEntry("topic", "小红书爆款");
		assertThat(data).containsKey("id");
	}

	@Test
	void createRejectsInvalidSourceType() {
		client().post().uri("/api/creation-drafts").header(header(), sign("user-a", null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("sourceType", "bogus", "title", "x"))
				.exchange().expectStatus().is4xxClientError();
	}

	@Test
	void createTaskDraftCarriesTaskReference() {
		Map<String, Object> response = client().post().uri("/api/creation-drafts")
				.header(header(), sign("user-task", null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("sourceType", "task", "title", "任务草稿", "taskId", "task-123", "taskVersion", 5,
						"platform", "xiaohongshu"))
				.exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
		Map<String, Object> data = (Map<String, Object>) response.get("data");
		assertThat(data).containsEntry("taskId", "task-123");
		assertThat(data).containsEntry("taskVersion", 5);
		assertThat(data).containsEntry("platform", "xiaohongshu");
	}

	@Test
	void listReturnsOnlyOwnDrafts() {
		createDraft("user-list", "independent", "草稿A");
		createDraft("user-list", "hot-topic", "草稿B");
		createDraft("user-other", "independent", "别人的");

		Map<String, Object> response = client().get().uri("/api/creation-drafts")
				.header(header(), sign("user-list", null)).exchange().expectStatus().isOk().expectBody(Map.class)
				.returnResult().getResponseBody();
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> items = (List<Map<String, Object>>) ((Map<String, Object>) response.get("data"))
				.get("items");
		assertThat(items).hasSize(2);
		assertThat(items).allSatisfy(item -> assertThat(item.get("title").toString()).startsWith("草稿"));
	}

	@Test
	void getReturns404ForOtherAccountDraft() {
		String draftId = createDraft("user-owner", "independent", "owner 的草稿");
		client().get().uri("/api/creation-drafts/" + draftId).header(header(), sign("user-stranger", null)).exchange()
				.expectStatus().isNotFound();
	}

	@Test
	void autosaveIncrementsVersionAndPersistsContent() {
		String draftId = createDraft("user-save", "independent", "自动保存测试");

		// 自动保存：写正文 + 乐观锁 expectedVersion=1
		client().put().uri("/api/creation-drafts/" + draftId).header(header(), sign("user-save", null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(
						Map.of("expectedVersion", 1, "title", "自动保存测试", "content", "这是正文内容", "status", "in_progress"))
				.exchange().expectStatus().isOk();

		// 详情应反映 v2 + 正文
		Map<String, Object> detail = getDraft(draftId, "user-save");
		assertThat(detail).containsEntry("version", 2);
		assertThat(detail).containsEntry("content", "这是正文内容");
		assertThat(detail).containsEntry("status", "in_progress");
	}

	@Test
	void autosaveRejectsStaleExpectedVersion() {
		String draftId = createDraft("user-lock", "independent", "锁测试");
		// 错误的 expectedVersion=99 → 409
		client().put().uri("/api/creation-drafts/" + draftId).header(header(), sign("user-lock", null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", 99, "title", "冲突", "content", "x")).exchange().expectStatus()
				.is4xxClientError();
	}

	@Test
	void deleteSoftDeletesAndHidesFromList() {
		String draftId = createDraft("user-del", "independent", "待删");
		client().delete().uri("/api/creation-drafts/" + draftId).header(header(), sign("user-del", null)).exchange()
				.expectStatus().isOk();

		// 列表不再含该草稿
		Map<String, Object> listResp = client().get().uri("/api/creation-drafts")
				.header(header(), sign("user-del", null)).exchange().expectStatus().isOk().expectBody(Map.class)
				.returnResult().getResponseBody();
		@SuppressWarnings("unchecked")
		List<?> items = (List<?>) ((Map<String, Object>) listResp.get("data")).get("items");
		assertThat(items).isEmpty();

		// 详情 404
		client().get().uri("/api/creation-drafts/" + draftId).header(header(), sign("user-del", null)).exchange()
				.expectStatus().isNotFound();
	}

	@Test
	void requiresAuthentication() {
		client().get().uri("/api/creation-drafts").exchange().expectStatus().isUnauthorized();
	}

	/** 自动保存落不可变旧版快照：v1 的正文进 creation_draft_version，可变行前进到 v2。 */
	@Test
	void autosaveAppendsImmutableVersionSnapshot() {
		String draftId = createDraft("user-snap", "independent", "快照测试");
		// v1 先写一段正文，这样 v2 保存时快照里能看到「旧」正文
		save(draftId, "user-snap", 1, "第一版正文");
		save(draftId, "user-snap", 2, "第二版正文");

		// 快照表应有 v1（原始空正文）与 v2（第一版正文）两行；v3 还在可变行里
		List<Map<String, Object>> snapshots = snapshotsOf(draftId);
		assertThat(snapshots).hasSize(2);
		assertThat(snapshots.get(0)).containsEntry("version", 1);
		assertThat(snapshots.get(0).get("content")).isNull();
		assertThat(snapshots.get(1)).containsEntry("version", 2);
		assertThat(snapshots.get(1)).containsEntry("content", "第一版正文");
		assertThat(snapshots).allSatisfy(row -> assertThat(row).containsEntry("snapshotted_by", "user-snap"));

		assertThat(getDraft(draftId, "user-snap")).containsEntry("version", 3);
		assertThat(getDraft(draftId, "user-snap")).containsEntry("content", "第二版正文");
	}

	@Test
	void versionsIncludeCurrentAndPaginateByVersionDescending() {
		String draftId = createDraft("user-history", "independent", "版本历史");
		save(draftId, "user-history", 1, "第一版正文");
		save(draftId, "user-history", 2, "第二版正文");

		Map<String, Object> first = versionList(draftId, "user-history", 2, null);
		List<Map<String, Object>> firstItems = (List<Map<String, Object>>) first.get("items");
		assertThat(firstItems).extracting(item -> item.get("version")).containsExactly(3, 2);
		assertThat(firstItems).allSatisfy(item -> assertThat(item).containsKeys("createdAt", "title"));
		assertThat(first.get("nextCursor")).isEqualTo("2");

		Map<String, Object> second = versionList(draftId, "user-history", 2, "2");
		List<Map<String, Object>> secondItems = (List<Map<String, Object>>) second.get("items");
		assertThat(secondItems).extracting(item -> item.get("version")).containsExactly(1);
		assertThat(second.get("nextCursor")).isNull();
	}

	@Test
	void versionDetailReturnsCompleteHistoricalSnapshot() {
		String draftId = createDraft("user-detail", "independent", "旧标题");
		client().put().uri("/api/creation-drafts/" + draftId).header(header(), sign("user-detail", null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", 1, "title", "新标题", "topic", "新主题", "articleTitle", "新文章标题",
						"outline", "新大纲", "content", "新正文", "platform", "wechat", "contentForm", "graphic", "status",
						"in_progress"))
				.exchange().expectStatus().isOk();

		Map<String, Object> oldVersion = versionDetail(draftId, "user-detail", 1);
		assertThat(oldVersion).containsEntry("version", 1).containsEntry("title", "旧标题")
				.containsEntry("sourceType", "independent").containsEntry("status", "draft")
				.containsEntry("topic", "测试主题").containsKey("createdAt");
		assertThat(oldVersion).doesNotContainKeys("content", "articleTitle", "outline");

		Map<String, Object> currentVersion = versionDetail(draftId, "user-detail", 2);
		assertThat(currentVersion).containsEntry("title", "新标题").containsEntry("topic", "新主题")
				.containsEntry("articleTitle", "新文章标题").containsEntry("outline", "新大纲").containsEntry("content", "新正文")
				.containsEntry("platform", "wechat").containsEntry("contentForm", "graphic")
				.containsEntry("status", "in_progress");
	}

	@Test
	void versionsHideOtherOwnersAndMissingVersions() {
		String draftId = createDraft("user-version-owner", "independent", "私有历史");

		client().get().uri("/api/creation-drafts/" + draftId + "/versions")
				.header(header(), sign("user-version-stranger", null)).exchange().expectStatus().isNotFound();
		client().get().uri("/api/creation-drafts/" + draftId + "/versions/99")
				.header(header(), sign("user-version-owner", null)).exchange().expectStatus().isNotFound();
	}

	@Test
	void historicalVersionsExposeNoWriteRoute() {
		String draftId = createDraft("user-version-readonly", "independent", "只读历史");
		client().put().uri("/api/creation-drafts/" + draftId + "/versions/1")
				.header(header(), sign("user-version-readonly", null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("title", "不得修改")).exchange().expectStatus().isEqualTo(405);
		client().delete().uri("/api/creation-drafts/" + draftId + "/versions/1")
				.header(header(), sign("user-version-readonly", null)).exchange().expectStatus().isEqualTo(405);
	}

	@Test
	void versionsRejectInvalidPagination() {
		String draftId = createDraft("user-version-page", "independent", "分页参数");
		client().get().uri("/api/creation-drafts/" + draftId + "/versions?limit=101")
				.header(header(), sign("user-version-page", null)).exchange().expectStatus().isBadRequest();
		client().get().uri("/api/creation-drafts/" + draftId + "/versions?cursor=0")
				.header(header(), sign("user-version-page", null)).exchange().expectStatus().isBadRequest();
	}

	/**
	 * 并发自动保存（跨设备 / debounce 抖动）：两个 PUT 带同一 expectedVersion， 一个 200 一个 409 —— 不能因
	 * creation_draft_version 主键冲突漏成 500。
	 */
	@Test
	void concurrentAutosaveYieldsConflictNotServerError() {
		String draftId = createDraft("user-race", "independent", "并发测试");

		List<Integer> statuses = Flux
				.merge(putStatus(draftId, "user-race", 1, "设备A"), putStatus(draftId, "user-race", 1, "设备B"))
				.collectList().block();

		assertThat(statuses).hasSize(2);
		assertThat(statuses).containsExactlyInAnyOrder(200, 409);
		// 可变行只前进一次
		assertThat(getDraft(draftId, "user-race")).containsEntry("version", 2);
		// 失败方回滚，快照表只留赢家那一行
		assertThat(snapshotsOf(draftId)).hasSize(1);
	}

	/** platform / contentForm 是 varchar(32)：超长应 400，不该漏到 Postgres 报 500。 */
	@Test
	void rejectsOverlongPlatformAndContentForm() {
		String tooLong = "x".repeat(33);
		client().post().uri("/api/creation-drafts").header(header(), sign("user-len", null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("sourceType", "independent", "title", "超长平台", "platform", tooLong)).exchange()
				.expectStatus().isBadRequest();

		String draftId = createDraft("user-len", "independent", "长度测试");
		client().put().uri("/api/creation-drafts/" + draftId).header(header(), sign("user-len", null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", 1, "title", "长度测试", "contentForm", tooLong)).exchange()
				.expectStatus().isBadRequest();
	}

	// ---- 任务书 #62：回答模式三列（content_mode / question_text / question_ref）----

	@Test
	void createPersistsAnswerModeAndQuestion() {
		Map<String, Object> response = client().post().uri("/api/creation-drafts")
				.header(header(), sign("user-answer", null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("sourceType", "independent", "title", "知乎回答草稿", "platform", "zhihu", "contentMode",
						"answer", "questionText", "大厂为什么开始弃用 Kubernetes", "questionRef", "1999041081275355787"))
				.exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();

		Map<String, Object> data = (Map<String, Object>) response.get("data");
		assertThat(data).containsEntry("contentMode", "answer");
		assertThat(data).containsEntry("questionText", "大厂为什么开始弃用 Kubernetes");
		assertThat(data).containsEntry("questionRef", "1999041081275355787");

		Map<String, Object> row = db
				.sql("SELECT content_mode, question_text, question_ref"
						+ " FROM creation_draft WHERE id=CAST(:id AS uuid)")
				.bind("id", data.get("id").toString()).fetch().one().block();
		assertThat(row).containsEntry("content_mode", "answer");
		assertThat(row).containsEntry("question_text", "大厂为什么开始弃用 Kubernetes");
		assertThat(row).containsEntry("question_ref", "1999041081275355787");
	}

	@Test
	void createDefaultsToArticleModeWhenOmitted() {
		Map<String, Object> data = (Map<String, Object>) client().post().uri("/api/creation-drafts")
				.header(header(), sign("user-default-mode", null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("sourceType", "independent", "title", "文章草稿")).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody().get("data");

		// 缺省即文章模式（灰度安全）；问题字段不下发
		assertThat(data).containsEntry("contentMode", "article");
		assertThat(data).doesNotContainKey("questionText");
		assertThat(data).doesNotContainKey("questionRef");
	}

	@Test
	void autosaveRoundTripsAnswerModeIntoCurrentRowAndSnapshot() {
		String draftId = createDraft("user-answer-save", "independent", "回答草稿");

		client().put().uri("/api/creation-drafts/" + draftId).header(header(), sign("user-answer-save", null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", 1, "title", "回答草稿", "platform", "zhihu", "contentMode", "answer",
						"questionText", "35 岁危机是真的吗", "questionRef", "123456", "content", "第一版回答正文"))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.contentMode").isEqualTo("answer")
				.jsonPath("$.data.questionText").isEqualTo("35 岁危机是真的吗");

		// 再保存一次，把上一版推进不可变快照表——历史版本必须一起带上模式与问题，
		// 否则「恢复到旧版本」会把回答草稿退化成文章
		client().put().uri("/api/creation-drafts/" + draftId).header(header(), sign("user-answer-save", null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", 2, "title", "回答草稿", "platform", "zhihu", "contentMode", "answer",
						"questionText", "35 岁危机是真的吗", "questionRef", "123456", "content", "第二版回答正文"))
				.exchange().expectStatus().isOk();

		Map<String, Object> snapshot = db
				.sql("SELECT content_mode, question_text, question_ref"
						+ " FROM creation_draft_version WHERE draft_id=CAST(:id AS uuid) AND version=2")
				.bind("id", draftId).fetch().one().block();
		assertThat(snapshot).containsEntry("content_mode", "answer");
		assertThat(snapshot).containsEntry("question_text", "35 岁危机是真的吗");
		assertThat(snapshot).containsEntry("question_ref", "123456");

		// 版本详情端点也回读到模式（前端「恢复此版本」据此还原）
		assertThat(versionDetail(draftId, "user-answer-save", 2)).containsEntry("contentMode", "answer")
				.containsEntry("questionText", "35 岁危机是真的吗");
	}

	@Test
	void rejectsUnknownContentMode() {
		client().post().uri("/api/creation-drafts").header(header(), sign("user-bad-mode", null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("sourceType", "independent", "title", "非法模式", "contentMode", "essay")).exchange()
				.expectStatus().isBadRequest();

		String draftId = createDraft("user-bad-mode", "independent", "非法模式保存");
		client().put().uri("/api/creation-drafts/" + draftId).header(header(), sign("user-bad-mode", null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", 1, "title", "非法模式保存", "contentMode", "essay")).exchange()
				.expectStatus().isBadRequest();
	}

	@Test
	void rejectsOverlongQuestionFields() {
		client().post().uri("/api/creation-drafts").header(header(), sign("user-q-len", null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("sourceType", "independent", "title", "超长问题",
						"contentMode", "answer", "questionText", "问".repeat(501)))
				.exchange().expectStatus().isBadRequest();

		client().post().uri("/api/creation-drafts").header(header(), sign("user-q-len", null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("sourceType", "independent", "title", "超长引用",
						"contentMode", "answer", "questionRef", "9".repeat(65)))
				.exchange().expectStatus().isBadRequest();
	}

	// ---- helpers ----

	private void save(String draftId, String account, int expectedVersion, String content) {
		client().put().uri("/api/creation-drafts/" + draftId).header(header(), sign(account, null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", expectedVersion, "title", "快照测试", "content", content)).exchange()
				.expectStatus().isOk();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> versionList(String draftId, String account, int limit, String cursor) {
		String uri = "/api/creation-drafts/" + draftId + "/versions?limit=" + limit
				+ (cursor == null ? "" : "&cursor=" + cursor);
		Map<String, Object> response = client().get().uri(uri).header(header(), sign(account, null)).exchange()
				.expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> versionDetail(String draftId, String account, int version) {
		Map<String, Object> response = client().get().uri("/api/creation-drafts/" + draftId + "/versions/" + version)
				.header(header(), sign(account, null)).exchange().expectStatus().isOk().expectBody(Map.class)
				.returnResult().getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	/** 发一个 PUT 只取状态码（并发测试用，不 expectStatus 免得先失败）。 */
	private Mono<Integer> putStatus(String draftId, String account, int expectedVersion, String content) {
		return Mono
				.fromCallable(() -> client().put().uri("/api/creation-drafts/" + draftId)
						.header(header(), sign(account, null)).contentType(MediaType.APPLICATION_JSON)
						.bodyValue(Map.of("expectedVersion", expectedVersion, "title", "并发测试", "content", content))
						.exchange().returnResult(Void.class).getStatus().value())
				.subscribeOn(Schedulers.boundedElastic());
	}

	/** 直读快照表（controller 尚未暴露草稿历史端点，§4.9.7 历史 UI 属后续切片）。 */
	private List<Map<String, Object>> snapshotsOf(String draftId) {
		return db
				.sql("SELECT version, content, snapshotted_by FROM creation_draft_version"
						+ " WHERE draft_id=CAST(:id AS uuid) ORDER BY version")
				.bind("id", draftId).fetch().all().map(row -> {
					Map<String, Object> copy = new java.util.LinkedHashMap<>();
					copy.put("version", row.get("version"));
					copy.put("content", row.get("content"));
					copy.put("snapshotted_by", row.get("snapshotted_by"));
					return copy;
				}).collectList().block();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> getDraft(String draftId, String account) {
		Map<String, Object> response = client().get().uri("/api/creation-drafts/" + draftId)
				.header(header(), sign(account, null)).exchange().expectStatus().isOk().expectBody(Map.class)
				.returnResult().getResponseBody();
		return (Map<String, Object>) response.get("data");
	}
}
