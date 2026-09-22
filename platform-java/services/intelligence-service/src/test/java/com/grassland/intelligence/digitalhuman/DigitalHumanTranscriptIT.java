package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 转写保存 IT（任务书 #105C C105C-04 / TC105C-04-01～04）：默认不保存/同意切换/删除迟到竞态/过期越权导出。
 */
class DigitalHumanTranscriptIT extends IntelligenceItSupport {

	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	static {
		REDIS.start();
	}

	@org.springframework.test.context.DynamicPropertySource
	static void redisProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
	}

	@Autowired
	private DatabaseClient db;

	@Autowired
	private DigitalHumanContentBuffer buffer;

	private final String account = "dh-c4-" + UUID.randomUUID();

	@BeforeEach
	void cleanShared() {
		db.sql("DELETE FROM dh_transcript").then().then(db.sql("DELETE FROM dh_event").then())
				.then(db.sql("DELETE FROM dh_operation WHERE kind LIKE 'transcript%'").then())
				.then(db.sql("DELETE FROM dh_session WHERE owner_account_id LIKE 'dh-c4-%'").then())
				.block(Duration.ofSeconds(10));
	}

	private String seedSession(String owner, boolean saveTranscript, boolean ended) {
		String id = UUID.randomUUID().toString();
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision,"
				+ " profile_name_at_creation, backend_id, preflight_id, controller_id, config_snapshot, state,"
				+ " state_entered_at, save_transcript, ended_at)"
				+ " VALUES (CAST(:id AS uuid), :o, gen_random_uuid(), 1, '角色', 'mock', gen_random_uuid(),"
				+ " gen_random_uuid(), '{}'::jsonb, :state, now(), :save,"
				+ " CASE WHEN :ended THEN now() ELSE NULL END)").bind("id", id).bind("o", owner)
				.bind("state", ended ? "ended" : "ready").bind("save", saveTranscript).bind("ended", ended).then()
				.block(Duration.ofSeconds(5));
		return id;
	}

	private void bufferFinal(String sessionId, String utteranceId, long seq, String text) {
		buffer.append(sessionId, 1,
				"{\"kind\":\"final\",\"utteranceId\":\"" + utteranceId + "\",\"utteranceSeq\":" + seq
						+ ",\"role\":\"assistant\",\"status\":\"complete\",\"text\":\"" + text + "\"}")
				.block(Duration.ofSeconds(5));
	}

	/** DELETE 带 JSON requestId（fetch 支持形态；用 method() 取得 body 接口）。 */
	private void deleteTranscript(String sessionId) {
		client().method(org.springframework.http.HttpMethod.DELETE)
				.uri("/api/digital-human/sessions/" + sessionId + "/transcript")
				.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"" + UUID.randomUUID() + "\"}").exchange().expectStatus().isEqualTo(202);
	}

	private WebTestClient.BodyContentSpec post(String uri, String body, int status) {
		return client().post().uri(uri).header("X-Grassland-Identity", sign(account, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isEqualTo(status)
				.expectBody();
	}

	// ---------- TC105C-04-01：默认不保存 ----------

	@Test
	void tc105c_04_01_defaultNeverPersistsConsentPersistsOnce() {
		// 场一：save=false，final 回调 + 结束 → 零持久正文。
		String without = seedSession(account, false, true);
		bufferFinal(without, UUID.randomUUID().toString(), 1, "未同意的文本");
		assertThat(countTranscripts(without)).isZero();

		// 场二：save=true，save 显式触发两次（同 utteranceId 只落一次）。
		String with = seedSession(account, true, true);
		bufferFinal(with, "11111111-2222-4111-8111-000000000001", 1, "已同意的最终句");
		post("/api/digital-human/sessions/" + with + "/transcript-save",
				"{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedVersion\":1}", 200);
		// 新 requestId 再次保存：缓冲已消费（同 utteranceId 只落一次）→ savedUtterances=0、行数不变。
		post("/api/digital-human/sessions/" + with + "/transcript-save",
				"{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedVersion\":2}", 200);
		assertThat(countTranscripts(with)).isEqualTo(1L);
		String stored = db.sql("SELECT final_text FROM dh_transcript WHERE session_id = CAST(:s AS uuid)")
				.bind("s", with).map(row -> row.get("final_text", String.class)).one().block(Duration.ofSeconds(5));
		assertThat(stored).isEqualTo("已同意的最终句");
	}

	// ---------- TC105C-04-02：同意切换 ----------

	@Test
	void tc105c_04_02_consentToggleOnlySavesDuringWindow() {
		String sessionId = seedSession(account, false, true);
		bufferFinal(sessionId, "11111111-2222-4111-8111-000000000002", 1, "关闭期句子");
		// 打开（false→true）：save 复制尚在缓冲的最终文本（追溯）。
		client().patch().uri("/api/digital-human/sessions/" + sessionId + "/transcript-preference")
				.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedVersion\":1,"
						+ "\"saveTranscript\":true}")
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.saveTranscript").isEqualTo(true);
		post("/api/digital-human/sessions/" + sessionId + "/transcript-save",
				"{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedVersion\":2}", 200);
		assertThat(countTranscripts(sessionId)).isEqualTo(1L);

		// 关闭（true→false）：只停后续，不删除已保存。
		client().patch().uri("/api/digital-human/sessions/" + sessionId + "/transcript-preference")
				.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedVersion\":3,"
						+ "\"saveTranscript\":false}")
				.exchange().expectStatus().isOk();
		assertThat(countTranscripts(sessionId)).isEqualTo(1L);
	}

	// ---------- TC105C-04-03：删除迟到竞态 ----------

	@Test
	void tc105c_04_03_tombstoneBlocksLateFinalsAndRevival() {
		String sessionId = seedSession(account, true, true);
		bufferFinal(sessionId, "11111111-2222-4111-8111-000000000003", 1, "删除前句子");
		post("/api/digital-human/sessions/" + sessionId + "/transcript-save",
				"{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedVersion\":1}", 200);
		assertThat(countTranscripts(sessionId)).isEqualTo(1L);

		// 删除墓碑 → 立即不可读、行清除、后续保存/偏好 409。
		deleteTranscript(sessionId);
		client().get().uri("/api/digital-human/sessions/" + sessionId + "/transcript")
				.header("X-Grassland-Identity", sign(account, null)).exchange().expectStatus().isEqualTo(409)
				.expectBody().jsonPath("$.code").isEqualTo("dh_content_deleted");
		assertThat(countTranscripts(sessionId)).isZero();

		// 迟到 final（旧 contentEpoch 缓冲重放）与重放 save → 不复活。
		bufferFinal(sessionId, "11111111-2222-4111-8111-000000000004", 2, "迟到句子");
		post("/api/digital-human/sessions/" + sessionId + "/transcript-save",
				"{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedVersion\":2}", 409);
		assertThat(countTranscripts(sessionId)).isZero();

		// 重复删除（新 requestId；幂等同终态）。
		deleteTranscript(sessionId);
	}

	// ---------- TC105C-04-04：过期/越权/导出 ----------

	@Test
	void tc105c_04_04_expiryCrossAccountAndExport() {
		// 结束超 10 分钟 → 保存 410 dh_content_expired。
		String expired = seedSession(account, true, true);
		db.sql("UPDATE dh_session SET ended_at = now() - interval '11 minutes'" + " WHERE id = CAST(:id AS uuid)")
				.bind("id", expired).then().block(Duration.ofSeconds(5));
		bufferFinal(expired, "11111111-2222-4111-8111-000000000005", 1, "过期句子");
		post("/api/digital-human/sessions/" + expired + "/transcript-save",
				"{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedVersion\":1}", 410);

		// 正常场：保存后导出（角色/UTC/状态标注）；B 请求 A 的 txt/列表 → 404。
		String sessionId = seedSession(account, true, true);
		bufferFinal(sessionId, "11111111-2222-4111-8111-000000000006", 1, "导出第一句");
		bufferFinal(sessionId, "11111111-2222-4111-8111-000000000007", 2, "导出第二句");
		post("/api/digital-human/sessions/" + sessionId + "/transcript-save",
				"{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedVersion\":1}", 200);
		byte[] exported = client().get().uri("/api/digital-human/sessions/" + sessionId + "/transcript/export")
				.header("X-Grassland-Identity", sign(account, null)).exchange().expectStatus().isOk().expectHeader()
				.contentTypeCompatibleWith("text/plain").expectHeader()
				.value("Content-Disposition", value -> assertThat(value).contains("attachment"))
				.expectBody(byte[].class).returnResult().getResponseBody();
		String text = new String(exported == null ? new byte[0] : exported);
		assertThat(text).contains("[assistant").contains("complete] 导出第一句").contains("导出第二句");

		String other = "dh-c4-b-" + UUID.randomUUID();
		client().get().uri("/api/digital-human/sessions/" + sessionId + "/transcript/export")
				.header("X-Grassland-Identity", sign(other, null)).exchange().expectStatus().isNotFound();
		client().get().uri("/api/digital-human/sessions/" + sessionId + "/transcript")
				.header("X-Grassland-Identity", sign(other, null)).exchange().expectStatus().isNotFound();
	}

	private Long countTranscripts(String sessionId) {
		return db.sql("SELECT count(*) AS n FROM dh_transcript WHERE session_id = CAST(:s AS uuid)")
				.bind("s", sessionId).map(row -> row.get("n", Long.class)).one().defaultIfEmpty(0L)
				.block(Duration.ofSeconds(5));
	}
}
