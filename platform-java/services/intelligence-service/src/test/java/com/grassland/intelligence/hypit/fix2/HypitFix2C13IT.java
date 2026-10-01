package com.grassland.intelligence.hypit.fix2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.hypit.job.HypitJobEventRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository.JobRow;
import com.grassland.intelligence.hypit.project.HypitJson;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * C107F2-13（107-fix-2 §6.6）：统一 SSE 事件协议、增量游标与工作区恢复（F24/F06）。
 *
 * <p>
 * 真 PostgreSQL + 真实 HTTP SSE（WebTestClient 解码 {@code ServerSentEvent}）。
 *
 * <ul>
 * <li>TC-F2-13-01 完整 envelope + named events：id={jobUuid}:{sequence}、
 * sequence/type/projectId/jobId/at/data 全量；progress/output/terminal 逐帧送达，
 * terminal 后流完成。</li>
 * <li>TC-F2-13-02 运行中 job 无新事件观测 10 秒：增量查询≤11 次（1 秒节奏，不忙循环）， 历史不重发（reset
 * 之外零数据帧）。</li>
 * <li>TC-F2-13-03 已确认 sequence=7 断线重连：Last-Event-ID={jobId}:7 严格大于续播 （首个数据帧
 * sequence=8，7 不重复），terminal 后停止。</li>
 * <li>TC-F2-13-04 旧 evt-{jobUuid}-{sequence} 可解析续播；跨 job cursor 与超可用历史 cursor
 * 一律 snapshot reset（权威 job + latestSequence），不泄漏他 job 事件。</li>
 * </ul>
 */
@TestPropertySource(properties = {"hypit.enabled=true"})
class HypitFix2C13IT extends IntelligenceItSupport {

	private static final String OWNER = "eeeeeeee-0000-4000-8000-00000000013a";
	private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE = new ParameterizedTypeReference<>() {
	};

	@Autowired
	HypitJobRepository jobs;

	@MockitoSpyBean
	HypitJobEventRepository events;

	@Autowired
	DatabaseClient db;

	private UUID projectId;
	private UUID jobId;

	@BeforeEach
	void seed() {
		cleanup();
		clearInvocations(events);
		projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'fix2-c13', 'clone', 'ready', 1)")
				.bind("id", projectId.toString()).bind("owner", OWNER).bind("ws", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(10));
		jobId = seedJob("running");
	}

	@AfterEach
	void sweep() {
		cleanup();
	}

	private void cleanup() {
		db.sql("DELETE FROM hypit_job_event WHERE job_id IN (SELECT id FROM hypit_job WHERE account_id = :o)")
				.bind("o", OWNER).then()
				.then(db.sql("DELETE FROM hypit_job WHERE account_id = :o").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id = :o").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id = :o").bind("o", OWNER).then())
				.block(Duration.ofSeconds(20));
	}

	private UUID seedJob(String state) {
		JobRow row = new JobRow(UUID.randomUUID(), null, projectId, OWNER, "build.submit", state, "pending", null, null,
				null, null, 0, 1, null, null, 1L, null, null, null, null, Instant.now(), Instant.now());
		return jobs.insert(row).block(Duration.ofSeconds(10)).id();
	}

	private void append(String type, String payloadJson) {
		events.append(jobId, type, payloadJson).block(Duration.ofSeconds(10));
	}

	private Flux<ServerSentEvent<String>> stream(String lastEventId) {
		WebTestClient.RequestHeadersSpec<?> spec = client().get()
				.uri("/api/hypit/projects/{p}/jobs/{j}/events", projectId, jobId)
				.header("X-Grassland-Identity", sign(OWNER, null)).accept(MediaType.TEXT_EVENT_STREAM);
		if (lastEventId != null) {
			spec = spec.header("Last-Event-ID", lastEventId);
		}
		return spec.exchange().expectStatus().isOk().returnResult(SSE).getResponseBody();
	}

	private static Map<String, Object> envelopeData(ServerSentEvent<String> frame) {
		return HypitJson.read(frame.data());
	}

	@Test
	@DisplayName("TC-F2-13-01：完整 envelope + named events，terminal 后流完成")
	void tc01EnvelopedNamedEventsTerminal() {
		StepVerifier.create(stream(null)).expectNextMatches(frame -> {
			assertThat(frame.event()).isEqualTo("snapshot");
			Map<String, Object> envelope = envelopeData(frame);
			assertThat(envelope.get("type")).isEqualTo("snapshot");
			Map<String, Object> data = HypitJson.mapValue(envelope.get("data"));
			assertThat(data.get("reset")).isEqualTo(true);
			assertThat(HypitJson.mapValue(data.get("job")).get("id")).isEqualTo(jobId.toString());
			assertThat(((Number) data.get("latestSequence")).longValue()).isZero();
			return true;
		}).then(() -> append("progress", "{\"phase\":\"compiling\"}")).expectNextMatches(frame -> {
			assertThat(frame.event()).isEqualTo("progress");
			assertThat(frame.id()).isEqualTo(jobId + ":1");
			Map<String, Object> envelope = envelopeData(frame);
			assertThat(((Number) envelope.get("sequence")).longValue()).isOne();
			assertThat(envelope.get("type")).isEqualTo("progress");
			assertThat(envelope.get("projectId")).isEqualTo(projectId.toString());
			assertThat(envelope.get("jobId")).isEqualTo(jobId.toString());
			assertThat(envelope.get("at")).isNotNull();
			assertThat(HypitJson.mapValue(envelope.get("data")).get("phase")).isEqualTo("compiling");
			return true;
		}).then(() -> append("output", "{\"buildId\":\"" + UUID.randomUUID() + "\",\"name\":\"final.video\"}"))
				.expectNextMatches(frame -> {
					assertThat(frame.event()).isEqualTo("output");
					Map<String, Object> envelope = envelopeData(frame);
					assertThat(((Number) envelope.get("sequence")).longValue()).isEqualTo(2L);
					assertThat(envelope.get("buildId")).isNotNull();
					assertThat(HypitJson.mapValue(envelope.get("data")).get("name")).isEqualTo("final.video");
					return true;
				}).then(() -> {
					append("terminal", "{\"state\":\"succeeded\"}");
					jobs.updateState(jobId, "succeeded", null, null).block(Duration.ofSeconds(10));
				}).expectNextMatches(frame -> {
					assertThat(frame.event()).isEqualTo("terminal");
					assertThat(frame.id()).isEqualTo(jobId + ":3");
					assertThat(HypitJson.mapValue(envelopeData(frame).get("data")).get("state")).isEqualTo("succeeded");
					return true;
				}).expectComplete().verify(Duration.ofSeconds(30));
	}

	@Test
	@DisplayName("TC-F2-13-02：10 秒无新事件——增量查询≤11 次、历史不重发")
	void tc02PacedIncrementalPollingNoResend() {
		append("progress", "{\"phase\":\"analyzing\"}");
		append("progress", "{\"phase\":\"compiling\"}");
		append("progress", "{\"phase\":\"packaging\"}");
		List<ServerSentEvent<String>> frames = stream(null).take(Duration.ofMillis(10_200)).collectList()
				.block(Duration.ofSeconds(20));
		assertThat(frames).isNotNull();
		// 初始订阅只发 snapshot reset（权威 job + latestSequence=3），3 条历史已折叠，不逐帧重发。
		assertThat(frames).hasSize(1);
		Map<String, Object> reset = HypitJson.mapValue(envelopeData(frames.get(0)).get("data"));
		assertThat(((Number) reset.get("latestSequence")).longValue()).isEqualTo(3L);
		// 1 秒一次增量（10 秒窗口 10 次）+ 初始 backlog 1 次 = ≤11；无忙循环。
		verify(events, atMost(11)).listAfter(any(), anyLong(), anyInt());
	}

	@Test
	@DisplayName("TC-F2-13-03：Last-Event-ID={jobId}:7 严格大于续播，terminal 即停")
	void tc03ResumeFromConfirmedCursor() throws InterruptedException {
		for (int i = 1; i <= 7; i++) {
			append("progress", "{\"phase\":\"step-" + i + "\"}");
		}
		// 有效 cursor 无 reset 首帧可同步：StepVerifier 的 leading then 会先于远端流建立执行，
		// 追加改走外部线程（订阅建立后 300ms 注入 sequence=8）。
		Thread appender = new Thread(() -> {
			try {
				Thread.sleep(300);
				append("progress", "{\"phase\":\"step-8\"}");
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
			}
		}, "c13-tc03-appender");
		appender.setDaemon(true);
		appender.start();
		StepVerifier.create(stream(jobId + ":7")).expectNextMatches(frame -> {
			// 已确认 7 不重复：首个数据帧必须是 sequence=8（服务端只发严格大于 cursor 的帧）。
			assertThat(frame.event()).isEqualTo("progress");
			Map<String, Object> envelope = envelopeData(frame);
			assertThat(((Number) envelope.get("sequence")).longValue()).isEqualTo(8L);
			assertThat(frame.id()).isEqualTo(jobId + ":8");
			assertThat(HypitJson.mapValue(envelope.get("data")).get("phase")).isEqualTo("step-8");
			return true;
		}).then(() -> {
			append("terminal", "{\"state\":\"succeeded\"}");
			jobs.updateState(jobId, "succeeded", null, null).block(Duration.ofSeconds(10));
		}).expectNextMatches(frame -> {
			assertThat(frame.event()).isEqualTo("terminal");
			assertThat(((Number) envelopeData(frame).get("sequence")).longValue()).isEqualTo(9L);
			return true;
		}).expectComplete().verify(Duration.ofSeconds(30));
		appender.join(5_000);
	}

	@Test
	@DisplayName("TC-F2-13-04：旧 evt cursor 兼容续播；跨 job/超历史 cursor reset 不泄漏")
	void tc04LegacyCursorAndCrossJobReset() {
		for (int i = 1; i <= 5; i++) {
			append("progress", "{\"phase\":\"step-" + i + "\"}");
		}
		// a) 旧 evt-{jobUuid}-{sequence}：可解析、同 job、不 reset，从 4 续播。
		ServerSentEvent<String> legacyFirst = stream("evt-" + jobId + "-3").next().block(Duration.ofSeconds(10));
		assertThat(legacyFirst).isNotNull();
		assertThat(legacyFirst.event()).isEqualTo("progress");
		assertThat(((Number) envelopeData(legacyFirst).get("sequence")).longValue()).isEqualTo(4L);

		// b) 跨 job cursor：另一 job 的 id 不得续播到本 job，必须 snapshot reset。
		UUID strangerJob = seedJob("running");
		db.sql("INSERT INTO hypit_job_event(job_id, sequence, type, payload) VALUES (CAST(:j AS uuid), 1,"
				+ " 'progress', CAST('{\"phase\":\"stranger\"}' AS jsonb))").bind("j", strangerJob.toString()).then()
				.block(Duration.ofSeconds(10));
		List<ServerSentEvent<String>> crossFrames = stream(strangerJob + ":1").take(Duration.ofMillis(1_600))
				.collectList().block(Duration.ofSeconds(10));
		assertThat(crossFrames).isNotNull();
		assertThat(crossFrames).hasSize(1);
		assertThat(crossFrames.get(0).event()).isEqualTo("snapshot");
		Map<String, Object> crossData = HypitJson.mapValue(envelopeData(crossFrames.get(0)).get("data"));
		assertThat(crossData.get("reset")).isEqualTo(true);
		assertThat(HypitJson.mapValue(crossData.get("job")).get("id")).isEqualTo(jobId.toString());
		assertThat(((Number) crossData.get("latestSequence")).longValue()).isEqualTo(5L);
		assertThat(crossFrames.get(0).data()).doesNotContain("stranger");

		// c) 超出可用历史（cursor=99 > latest=5）：同样 reset 而非空转泄漏。
		ServerSentEvent<String> beyondFirst = stream(jobId + ":99").next().block(Duration.ofSeconds(10));
		assertThat(beyondFirst).isNotNull();
		assertThat(beyondFirst.event()).isEqualTo("snapshot");
		Map<String, Object> beyondData = HypitJson.mapValue(envelopeData(beyondFirst).get("data"));
		assertThat(beyondData.get("reset")).isEqualTo(true);
		assertThat(HypitJson.mapValue(beyondData.get("job")).get("id")).isEqualTo(jobId.toString());
	}
}
