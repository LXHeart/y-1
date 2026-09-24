package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

/**
 * 历史检索与异步删除 IT（任务书 #105G C105G-01 / TC105G-01-01～03）：keyset 分页与 UTC 边界、
 * 隔离与伪造游标、活动会话删除竞态（墓碑即时禁读/迟到不复活/资产独立/终止失败如实 running）。
 *
 * <p>
 * fake runtime transport 可切换 end 失败（TC105G-01-03：终止失败 → operation 停 running，
 * 恢复后同键重试续跑到 succeeded，不谎报完成）。
 */
class DigitalHumanHistoryIT extends IntelligenceItSupport {

	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	static {
		REDIS.start();
	}

	@org.springframework.test.context.DynamicPropertySource
	static void redisProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
	}

	/** TC105G-01-03 终止失败开关：fake end 抛错模拟 runtime 不可达。 */
	static final AtomicBoolean FAIL_ENDS = new AtomicBoolean(false);
	static final AtomicInteger RUNTIME_ENDS = new AtomicInteger();

	@org.springframework.boot.test.context.TestConfiguration
	static class FakeRuntimeConfig {

		@Bean
		@Primary
		DigitalHumanRuntimeClient fakeRuntime() {
			DigitalHumanRuntimeClient.Transport transport = new DigitalHumanRuntimeClient.Transport() {
				@Override
				public Mono<DigitalHumanRuntimeClient.RuntimeState> createSession(String sessionId, String backendId,
						UUID commandId) {
					return Mono.just(state(sessionId, "connecting"));
				}

				@Override
				public Mono<DigitalHumanRuntimeClient.RuntimeState> state(String sessionId) {
					return Mono.just(state(sessionId, "connecting"));
				}

				@Override
				public Mono<DigitalHumanRuntimeClient.RuntimeState> end(String sessionId, UUID commandId,
						String reasonCode) {
					RUNTIME_ENDS.incrementAndGet();
					if (FAIL_ENDS.get()) {
						return Mono.error(new IllegalStateException("runtime unreachable (test)"));
					}
					return Mono.just(state(sessionId, "ended"));
				}

				private DigitalHumanRuntimeClient.RuntimeState state(String sessionId, String state) {
					return new DigitalHumanRuntimeClient.RuntimeState(sessionId, "worker-fake", 1, 1, state, null,
							Instant.now().plusSeconds(30).toString(), false, false);
				}
			};
			return new DigitalHumanRuntimeClient(transport, new DigitalHumanRuntimeClient.Recorder() {
				@Override
				public void onCreate(String sessionId) {
				}

				@Override
				public void onEnd(String sessionId) {
				}
			});
		}
	}

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private DatabaseClient db;

	@Autowired
	private DigitalHumanContentBuffer buffer;

	private final String account = "dh-g1-" + UUID.randomUUID();
	private final String other = "dh-g1-b-" + UUID.randomUUID();

	@BeforeEach
	void cleanShared() {
		FAIL_ENDS.set(false);
		RUNTIME_ENDS.set(0);
		// FK 顺序：事件/转写/录制/轮次/调用 → operation → session → profile 域（本用例前缀账号）。
		db.sql("DELETE FROM dh_event").then().then(db.sql("DELETE FROM dh_transcript").then())
				.then(db.sql("DELETE FROM dh_recording").then()).then(db.sql("DELETE FROM dh_turn").then())
				.then(db.sql("DELETE FROM dh_invocation").then())
				.then(db.sql("DELETE FROM dh_operation WHERE kind IN ('session_delete','session_end')").then())
				.then(db.sql("DELETE FROM dh_session WHERE owner_account_id LIKE 'dh-g1-%'").then())
				.then(db.sql("DELETE FROM dh_profile_revision WHERE owner_account_id LIKE 'dh-g1-%'").then())
				.then(db.sql("DELETE FROM dh_profile WHERE owner_account_id LIKE 'dh-g1-%'").then())
				.block(Duration.ofSeconds(10));
	}

	// ---------- 造数 ----------

	private String seedSession(String owner, Instant createdAt, String state, boolean deleted) {
		String id = UUID.randomUUID().toString();
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision,"
				+ " profile_name_at_creation, backend_id, preflight_id, controller_id, config_snapshot, state,"
				+ " state_entered_at, created_at, updated_at, ended_at, deleted_at, content_epoch)"
				+ " VALUES (CAST(:id AS uuid), :o, gen_random_uuid(), 1, '角色', 'mock', gen_random_uuid(),"
				+ " gen_random_uuid(), CAST(:config AS jsonb), :state, :at, :at, :at,"
				+ " CASE WHEN :state IN ('ended','failed') THEN :at ELSE NULL END,"
				+ " CASE WHEN :deleted THEN :at ELSE NULL END, 1)").bind("id", id).bind("o", owner)
				.bind("config", "{\"v\":1,\"priceTableVersion\":\"pt-v1\"}").bind("state", state)
				.bind("at", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC)).bind("deleted", deleted).then()
				.block(Duration.ofSeconds(5));
		return id;
	}

	private void seedTranscriptRow(String sessionId, String text) {
		db.sql("INSERT INTO dh_transcript(id, owner_account_id, session_id, utterance_id, utterance_seq, role,"
				+ " final_text, status, started_at, ended_at, content_epoch) VALUES (gen_random_uuid(),"
				+ " (SELECT owner_account_id FROM dh_session WHERE id = CAST(:s AS uuid)), CAST(:s AS uuid),"
				+ " gen_random_uuid(), (SELECT COALESCE(max(utterance_seq), 0) + 1 FROM dh_transcript"
				+ " WHERE session_id = CAST(:s AS uuid)), 'user', :text, 'complete', now(), now(), 1)")
				.bind("s", sessionId).bind("text", text).then().block(Duration.ofSeconds(5));
	}

	private void seedEventRow(String sessionId) {
		db.sql("INSERT INTO dh_event(session_id, seq, event_id, event_type, payload, owner_account_id)"
				+ " VALUES (CAST(:s AS uuid), (SELECT last_seq + 1 FROM dh_session WHERE id = CAST(:s AS uuid)),"
				+ " gen_random_uuid(), 'session.state', '{}'::jsonb,"
				+ " (SELECT owner_account_id FROM dh_session WHERE id = CAST(:s AS uuid)))").bind("s", sessionId).then()
				.then(db.sql("UPDATE dh_session SET last_seq = last_seq + 1 WHERE id = CAST(:s AS uuid)")
						.bind("s", sessionId).then())
				.block(Duration.ofSeconds(5));
	}

	/** withAsset=true 表示已挂 asset 的保存段（saved）。 */
	private String seedRecording(String sessionId, String state, boolean withAsset) {
		String id = UUID.randomUUID().toString();
		String asset = withAsset ? UUID.randomUUID().toString() : null;
		var spec = db
				.sql("INSERT INTO dh_recording(id, owner_account_id, session_id, start_command_id, state,"
						+ " start_program_ms, asset_id, expires_at) VALUES (CAST(:id AS uuid),"
						+ " (SELECT owner_account_id FROM dh_session WHERE id = CAST(:s AS uuid)), CAST(:s AS uuid),"
						+ " gen_random_uuid(), :state, 0, CAST(:asset AS uuid), now() + interval '1 day')")
				.bind("id", id).bind("s", sessionId).bind("state", state);
		spec = asset == null ? spec.bindNull("asset", String.class) : spec.bind("asset", asset);
		spec.then().block(Duration.ofSeconds(5));
		return id;
	}

	private void seedPendingInvocation(String sessionId) {
		// render 形状（K05 CHECK）：挂 session、不挂 turn、segment=0。
		db.sql("""
				INSERT INTO dh_invocation(id, owner_account_id, session_id, turn_id, stage, resource_id,
				    segment_index, operation_id, state, settlement_state, provider_snapshot, budget_snapshot,
				    request_hash, deadline_at)
				VALUES (gen_random_uuid(), (SELECT owner_account_id FROM dh_session WHERE id = CAST(:s AS uuid)),
				    CAST(:s AS uuid), NULL, 'render', gen_random_uuid(), 0, gen_random_uuid(),
				    'unknown', 'pending', '{"type":"PLATFORM"}'::jsonb, '{"feature":null}'::jsonb,
				    repeat('0', 64), now() + interval '1 hour')
				""").bind("s", sessionId).then().block(Duration.ofSeconds(5));
	}

	private void seedBufferFinal(String sessionId, String utteranceId, long seq, String text) {
		buffer.append(sessionId, 1,
				"{\"kind\":\"final\",\"utteranceId\":\"" + utteranceId + "\",\"utteranceSeq\":" + seq
						+ ",\"role\":\"assistant\",\"status\":\"complete\",\"text\":\"" + text + "\"}")
				.block(Duration.ofSeconds(5));
	}

	// ---------- HTTP 助手 ----------

	private byte[] listRaw(String query, String owner, int status) {
		return client().get().uri("/api/digital-human/sessions" + (query.isEmpty() ? "" : "?" + query))
				.header("X-Grassland-Identity", sign(owner, null)).exchange().expectStatus().isEqualTo(status)
				.expectBody(byte[].class).returnResult().getResponseBodyContent();
	}

	private byte[] deleteRaw(String sessionId, String owner, UUID requestId, int status) {
		return client().method(HttpMethod.DELETE).uri("/api/digital-human/sessions/" + sessionId)
				.header("X-Grassland-Identity", sign(owner, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"" + requestId + "\"}").exchange().expectStatus().isEqualTo(status)
				.expectBody(byte[].class).returnResult().getResponseBodyContent();
	}

	private JsonNode parse(byte[] raw) {
		try {
			return JSON.readTree(raw == null ? new byte[0] : raw);
		} catch (Exception failure) {
			throw new IllegalStateException(failure);
		}
	}

	private List<String> itemIds(JsonNode page) {
		List<String> ids = new ArrayList<>();
		for (JsonNode item : page.at("/data/items")) {
			ids.add(item.get("id").asText());
		}
		return ids;
	}

	private JsonNode itemOf(JsonNode page, String id) {
		for (JsonNode item : page.at("/data/items")) {
			if (id.equals(item.get("id").asText())) {
				return item;
			}
		}
		throw new AssertionError("列表中找不到会话 " + id);
	}

	// ---------- TC105G-01-01：分页与 UTC ----------

	@Test
	void tc105g_01_01_paginationAndUtcHalfOpenWindow() {
		// 北京时间 2026-09-23 00:00 = 2026-09-22T16:00:00Z：跨午夜两侧的确定性时间点。
		Instant midnightUtc = Instant.parse("2026-09-22T16:00:00Z");
		Set<String> expected = new LinkedHashSet<>();
		// 午夜前 15:59:59.999Z 两场（同毫秒不同 id：keyset 次键裁定不重不漏）。
		String tieA = seedSession(account, midnightUtc.minusMillis(1), "ended", false);
		String tieB = seedSession(account, midnightUtc.minusMillis(1), "ended", false);
		expected.add(tieA);
		expected.add(tieB);
		// 恰在 from 时刻的行（半开下界含）。
		String atFrom = seedSession(account, midnightUtc, "ended", false);
		expected.add(atFrom);
		// 午夜起 18 场（ended/failed 混合——列表不过滤状态除非显式过滤）。
		for (int i = 0; i < 18; i++) {
			expected.add(seedSession(account, midnightUtc.plusSeconds(i + 1), i % 3 == 0 ? "failed" : "ended", false));
		}
		// 第 22 场：已保存转写 + 两段录制（1 临时 + 1 已存 asset）+ 一笔 pending 费用。
		String rich = seedSession(account, midnightUtc.plusSeconds(60), "ended", false);
		expected.add(rich);
		seedTranscriptRow(rich, "已同意保存的句子");
		seedRecording(rich, "ready", false);
		seedRecording(rich, "saved", true);
		seedPendingInvocation(rich);
		// 已删除行与他人行：不出现在 A 列表。
		seedSession(account, midnightUtc.plusSeconds(61), "ended", true);
		seedSession(other, midnightUtc.plusSeconds(62), "ended", false);

		// 第一页 20 条 + 游标；第二页 2 条：不重不漏。
		JsonNode first = parse(listRaw("limit=20", account, 200));
		List<String> pageOne = itemIds(first);
		assertThat(pageOne).hasSize(20);
		String cursor = first.at("/data/nextCursor").asText();
		assertThat(cursor).isNotBlank();
		JsonNode second = parse(listRaw("limit=20&cursor=" + cursor, account, 200));
		List<String> pageTwo = itemIds(second);
		assertThat(pageTwo).hasSize(2);
		Set<String> union = new LinkedHashSet<>(pageOne);
		union.addAll(pageTwo);
		assertThat(union).isEqualTo(expected);
		assertThat(second.at("/data/nextCursor").isNull()).isTrue();
		// 同毫秒并列：按 id 倒序稳定（两页合并序无重复即 union 断言；并列两行都在结果内）。
		assertThat(union).contains(tieA, tieB);

		// 汇总字段（rich 场）：转写/录制/资产/pending 各就位（费用 pending 保留，不以 0 冒充）。
		JsonNode richItem = itemOf(first, rich);
		assertThat(richItem.get("hasSavedTranscript").asBoolean()).isTrue();
		assertThat(richItem.get("recordingCount").asInt()).isEqualTo(2);
		assertThat(richItem.get("savedAssetCount").asInt()).isEqualTo(1);
		assertThat(richItem.at("/billing/pendingCount").asInt()).isEqualTo(1);
		assertThat(richItem.at("/billing/priceTableVersion").asText()).isEqualTo("pt-v1");
		assertThat(richItem.get("profileNameAtCreation").asText()).isEqualTo("角色");

		// UTC 半开 [from,to)：恰在 from 的行含、恰在 to 的行不含。
		String from = "2026-09-22T16:00:00Z";
		String to = "2026-09-22T16:00:59Z";
		JsonNode windowed = parse(listRaw("from=" + from + "&to=" + to, account, 200));
		assertThat(itemIds(windowed)).hasSize(19); // atFrom + +1..+18s；rich(+60s) 与 tie 行不在。
		// from=午夜：atFrom 含（半开下界）、午夜前毫秒行排除。
		JsonNode fromOnly = parse(listRaw("from=" + from, account, 200));
		List<String> fromIds = itemIds(fromOnly);
		assertThat(fromIds).doesNotContain(tieA, tieB).contains(atFrom);
		assertThat(fromIds).hasSize(20);

		// 非法输入：跨度 >90 天 / from==to / limit 越界 / 非法 state / 非法 profileId。
		assertThat(
				parse(listRaw("from=2026-01-01T00:00:00Z&to=2026-09-22T16:00:00Z", account, 422)).at("/code").asText())
				.isEqualTo("dh_invalid_input");
		listRaw("from=2026-09-22T16:00:00Z&to=2026-09-22T16:00:00Z", account, 422);
		listRaw("limit=0", account, 422);
		listRaw("limit=101", account, 422);
		listRaw("state=running", account, 422);
		listRaw("profileId=not-a-uuid", account, 422);
		// 合法状态过滤：failed 共 6 场（i%3==0，i=0..17）。
		assertThat(itemIds(parse(listRaw("state=failed", account, 200)))).hasSize(6);
	}

	// ---------- TC105G-01-02：隔离与伪造游标 ----------

	@Test
	void tc105g_01_02_isolationAndForgedCursor() {
		String aSession = seedSession(account, Instant.parse("2026-09-22T10:00:00Z"), "ended", false);
		String aOlder = seedSession(account, Instant.parse("2026-09-22T09:00:00Z"), "ended", false);
		String bSession = seedSession(other, Instant.parse("2026-09-22T11:00:00Z"), "ended", false);

		// B 列表（带 org 上下文断言：DH 域个人归属，org 不穿透）：只见 B 自己的行。
		byte[] bRaw = client().get().uri("/api/digital-human/sessions")
				.header("X-Grassland-Identity", signWithOrg(other, UUID.randomUUID().toString())).exchange()
				.expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBodyContent();
		assertThat(itemIds(parse(bRaw))).containsExactly(bSession);
		// B 借 A 的 profileId 过滤：不返 A 数据。
		String aProfile = db.sql("SELECT profile_id::text AS p FROM dh_session WHERE id = CAST(:s AS uuid)")
				.bind("s", aSession).map(row -> row.get("p", String.class)).one().block(Duration.ofSeconds(5));
		assertThat(itemIds(parse(listRaw("profileId=" + aProfile, other, 200)))).isEmpty();

		// B 删除 A 的会话：404（无权与不存在同文案）。
		assertThat(parse(deleteRaw(aSession, other, UUID.randomUUID(), 404)).at("/code").asText())
				.isEqualTo("dh_not_found");

		// B 持 A 的游标：owner 指纹不符 → 422，不返回 A 的页。
		// limit=1 → 下一页游标指向较旧场（A 有两场，游标必然存在）。
		JsonNode aFirst = parse(listRaw("limit=1", account, 200));
		assertThat(itemIds(aFirst)).containsExactly(aSession);
		String aCursor = aFirst.at("/data/nextCursor").asText();
		assertThat(aCursor).isNotBlank();
		assertThat(parse(listRaw("limit=1&cursor=" + aCursor, other, 422)).at("/code").asText())
				.isEqualTo("dh_invalid_input");
		// 伪造形状（乱码/超长）→ 422。
		listRaw("limit=1&cursor=%21%21notbase64", account, 422);
		listRaw("limit=1&cursor=" + "x".repeat(600), account, 422);

		// A 自己重放游标仍有效（合法路径回归：翻到较旧场）。
		assertThat(itemIds(parse(listRaw("limit=1&cursor=" + aCursor, account, 200)))).containsExactly(aOlder);
	}

	// ---------- TC105G-01-03：删除活动会话竞态 ----------

	@Test
	void tc105g_01_03_deleteActiveSessionTombstoneAndAssets() {
		String active = seedSession(account, Instant.now().minusSeconds(120), "ready", false);
		seedTranscriptRow(active, "删除前已保存句子一");
		seedTranscriptRow(active, "删除前已保存句子二");
		seedEventRow(active);
		seedBufferFinal(active, "11111111-2222-4111-8111-00000000000a", 1, "删除前缓冲句");
		String tempRecording = seedRecording(active, "ready", false);
		String savedRecording = seedRecording(active, "saved", true);
		seedPendingInvocation(active);

		// 终止失败（runtime 不可达）：墓碑与即时禁读先行，operation 停 running 不谎报完成。
		FAIL_ENDS.set(true);
		JsonNode first = parse(deleteRaw(active, account, UUID.randomUUID(), 202));
		assertThat(first.at("/data/state").asText()).isIn("pending", "running");
		assertThat(sessionField(active, "deleted_at")).isNotEqualTo("null");
		assertThat(sessionField(active, "content_deleted")).isEqualTo("true");
		assertThat(sessionField(active, "content_epoch")).isEqualTo("2");
		assertThat(countRows("dh_transcript", active)).isZero();
		assertThat(countRows("dh_event", active)).isZero();
		assertThat(recordingState(tempRecording)).isEqualTo("deleted");
		assertThat(recordingState(savedRecording)).isEqualTo("saved");
		// 即时禁读：转写读 409 墓碑（旧链接不复活）。
		assertThat(parse(client().get().uri("/api/digital-human/sessions/" + active + "/transcript")
				.header("X-Grassland-Identity", sign(account, null)).exchange().expectStatus().isEqualTo(409)
				.expectBody(byte[].class).returnResult().getResponseBodyContent()).at("/code").asText())
				.isEqualTo("dh_content_deleted");
		// 列表已不可见。
		assertThat(itemIds(parse(listRaw("limit=100", account, 200)))).isEmpty();

		// 迟到 final（旧 epoch 缓冲重放）+ 保存 → 墓碑 409，不复活。
		seedBufferFinal(active, "11111111-2222-4111-8111-00000000000b", 2, "迟到句子");
		assertThat(parse(client().post().uri("/api/digital-human/sessions/" + active + "/transcript-save")
				.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedVersion\":1}").exchange()
				.expectStatus().isEqualTo(409).expectBody(byte[].class).returnResult().getResponseBodyContent())
				.at("/code").asText()).isEqualTo("dh_content_deleted");
		assertThat(countRows("dh_transcript", active)).isZero();

		// 并发第二个删除（新 requestId，终止仍失败）：受理不重复清理、停 running。
		int endsBefore = RUNTIME_ENDS.get();
		UUID secondRequestId = UUID.randomUUID();
		JsonNode second = parse(deleteRaw(active, account, secondRequestId, 202));
		assertThat(second.at("/data/state").asText()).isIn("pending", "running");

		// runtime 恢复后同键重放（requestId2）：续跑收尾 → ended + succeeded。
		FAIL_ENDS.set(false);
		JsonNode replay = parse(deleteRaw(active, account, secondRequestId, 202));
		assertThat(replay.at("/data/state").asText()).isEqualTo("succeeded");
		assertThat(sessionField(active, "state")).isEqualTo("ended");
		assertThat(sessionField(active, "ended_at")).isNotEqualTo("null");
		assertThat(recordingState(savedRecording)).isEqualTo("saved");
		// 已保存资产/账务独立：saved 段与 asset 引用原样保留。
		String savedAsset = db.sql("SELECT asset_id::text AS a FROM dh_recording WHERE id = CAST(:r AS uuid)")
				.bind("r", savedRecording).map(row -> row.get("a", String.class)).one().block(Duration.ofSeconds(5));
		assertThat(savedAsset).isNotBlank();
		// 缓冲清理后不可再读出旧正文。
		List<String> remaining = buffer.read(active, 1).block(Duration.ofSeconds(5));
		assertThat(remaining).isEmpty();
		assertThat(RUNTIME_ENDS.get()).isGreaterThan(endsBefore);

		// 全部收口后的新删除请求：直接 succeeded（已终态、无 runtime 参与）。
		JsonNode third = parse(deleteRaw(active, account, UUID.randomUUID(), 202));
		assertThat(third.at("/data/state").asText()).isEqualTo("succeeded");

		// 已终态会话删除（从未活动路径）：同步完成、正文清除、状态保持 ended。
		String ended = seedSession(account, Instant.now().minusSeconds(600), "ended", false);
		seedTranscriptRow(ended, "终态会话句子");
		JsonNode endedDelete = parse(deleteRaw(ended, account, UUID.randomUUID(), 202));
		assertThat(endedDelete.at("/data/state").asText()).isEqualTo("succeeded");
		assertThat(countRows("dh_transcript", ended)).isZero();
		assertThat(sessionField(ended, "state")).isEqualTo("ended");
	}

	// ---------- 查询助手 ----------

	private String sessionField(String sessionId, String column) {
		Object value = db.sql("SELECT " + column + " AS v FROM dh_session WHERE id = CAST(:s AS uuid)")
				.bind("s", sessionId).map(row -> row.get("v", Object.class)).one().block(Duration.ofSeconds(5));
		return value == null ? "null" : String.valueOf(value);
	}

	private long countRows(String table, String sessionId) {
		Long count = db.sql("SELECT count(*) AS n FROM " + table + " WHERE session_id = CAST(:s AS uuid)")
				.bind("s", sessionId).map(row -> row.get("n", Long.class)).one().defaultIfEmpty(0L)
				.block(Duration.ofSeconds(5));
		return count == null ? 0 : count;
	}

	private String recordingState(String recordingId) {
		return db.sql("SELECT state AS v FROM dh_recording WHERE id = CAST(:r AS uuid)").bind("r", recordingId)
				.map(row -> row.get("v", String.class)).one().block(Duration.ofSeconds(5));
	}
}
