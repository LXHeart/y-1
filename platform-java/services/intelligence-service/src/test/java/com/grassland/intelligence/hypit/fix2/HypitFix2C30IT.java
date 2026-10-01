package com.grassland.intelligence.hypit.fix2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.hypit.template.HypitPackageTransferService;
import com.grassland.intelligence.hypit.template.HypitProjectPackageService;
import com.grassland.intelligence.security.IntelligenceException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * C107F2-30（F14/F31 / §6.13、§8.3）：工程包浏览器下载、上传导入与进度反馈。 真 PostgreSQL + WireMock
 * sidecar 桩；zip 打包/解包字节保真与 Range/416 由 broker 侧真值（fix2-c28 组 + 传送码本 ditto/unzip
 * 冒烟，见 test-artifacts task-107/fix2/C30/zip-codec-smoke.mts），本卡锁 Java 编排与授权面。
 *
 * <ul>
 * <li>TC-F2-30-01 导出 job → 状态面给出 owner 绑定 downloadPath（无 artifactRoot 出现在 HTTP
 * 形状），/package 取回的是真实可解压 zip（manifest/hash 正确），不只 202。</li>
 * <li>TC-F2-30-02 下载的 zip 经上传导入：新 owner 工程 ready，可打开（revision/selected_run
 * 落定）。</li>
 * <li>TC-F2-30-03 A 的 exportId：B 查状态/取流均 404 无字节；匿名等同他人。</li>
 * <li>TC-F2-30-04 上传转发失败：无 ready 假工程；同 requestId 重试收敛同一导入； Range 头原样透传给
 * broker。</li>
 * </ul>
 */
@TestPropertySource(properties = {"hypit.enabled=true"})
class HypitFix2C30IT extends IntelligenceItSupport {

	private static final String OWNER_A = "30000000-0000-4000-8000-00000000000a";
	private static final String OWNER_B = "30000000-0000-4000-8000-00000000000b";
	private static final String ZIP_SHA = "c".repeat(64);
	private static final WireMockServer SIDECAR = new WireMockServer(0);

	static {
		SIDECAR.start();
	}

	@AfterAll
	static void stopSidecar() {
		SIDECAR.stop();
	}

	@Autowired
	HypitProjectPackageService packages;

	@Autowired
	HypitPackageTransferService transfers;

	@Autowired
	DatabaseClient db;

	@org.springframework.test.context.DynamicPropertySource
	static void sidecarProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("hypit.sidecar-base-url", SIDECAR::baseUrl);
		registry.add("hypit.internal-token", () -> "it-hypit-internal-token-0123456789abcdef");
	}

	@BeforeEach
	void clean() {
		SIDECAR.resetAll();
		for (String owner : List.of(OWNER_A, OWNER_B)) {
			db.sql("DELETE FROM hypit_revision WHERE project_id IN (SELECT id FROM hypit_project"
					+ " WHERE account_id = :o)").bind("o", owner).then()
					.then(db.sql("DELETE FROM hypit_job WHERE account_id = :o").bind("o", owner).then())
					.then(db.sql("DELETE FROM hypit_command WHERE account_id = :o").bind("o", owner).then())
					.then(db.sql("DELETE FROM hypit_project WHERE account_id = :o").bind("o", owner).then())
					.block(Duration.ofSeconds(20));
		}
	}

	/** JDK 标准流式工具产真实 zip（与 §6.13 的 Java 侧编解码同源）。 */
	private static byte[] realZip() {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
			zip.putNextEntry(new ZipEntry("hypit-project.json"));
			zip.write("{\"format\":\"y1.hypit-project@1\"}".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("main.svrun"));
			zip.write("run main".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
		return bytes.toByteArray();
	}

	private void stubExportSuccess(long revision) {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands"))
				.withRequestBody(containing("project-package.export"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"project-package.export","state":"succeeded","result":{
						  "artifactRoot":"project-exports/x/rev-1","fileCount":2,
						  "zipSha256":"%s","zipSizeBytes":%d,"zipName":"hypit-project-abc.zip",
						  "manifest":{"format":"y1.hypit-project@1","sourceCommit":"%s",
						    "project":{"title":"导出","revision":%d,"selectedRun":"main.svrun"},
						    "files":[],"packages":[],"results":[],"omitted":[]}}}
						""".formatted(ZIP_SHA, realZipLength(), "2".repeat(40), revision))));
	}

	private static int realZipLength;

	private static int realZipLength() {
		if (realZipLength == 0) {
			try {
				realZipLength = realZip().length;
			} catch (Exception error) {
				throw new IllegalStateException(error);
			}
		}
		return realZipLength;
	}

	private void stubImportSuccess(long revision) {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands"))
				.withRequestBody(containing("project-package.import"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"project-package.import","state":"succeeded","result":{
						  "projectId":"broker-side","revision":%d,"manifestHash":"%s",
						  "fileCount":2,"selectedRun":"main.svrun","artifactRoot":"project-exports/y/rev-2"}}
						""".formatted(revision, "d".repeat(64)))));
	}

	// ── TC-F2-30-01：导出 job → 真实 zip 下载（不只 202） ────────────────────────
	@Test
	@DisplayName("TC-F2-30-01 导出：download 元数据 owner 绑定；package 取回真实可解压 zip")
	void exportJobThenRealZipDownload() throws Exception {
		stubExportSuccess(3L);
		Map<String, Object> accepted = packages
				.export(OWNER_A, UUID.randomUUID(), UUID.randomUUID(), "导出", null, "main.svrun")
				.block(Duration.ofSeconds(20));
		assertThat(accepted).isNotNull();
		// 202 形状：jobId/exportId/status；HTTP 面不带 artifactRoot（控制器裁剪，服务面审计保留）。
		assertThat(accepted.get("exportId")).isNotNull();
		assertThat(accepted.get("jobId")).isNotNull();
		assertThat(accepted.get("status")).isEqualTo("succeeded");
		UUID exportId = UUID.fromString(String.valueOf(accepted.get("exportId")));

		// 状态面：owner 查到 download（24h TTL、sizeBytes/sha256 齐备）。
		Map<String, Object> status = transfers.exportStatus(OWNER_A, exportId).block(Duration.ofSeconds(20));
		assertThat(status).isNotNull();
		assertThat(status.get("status")).isEqualTo("succeeded");
		@SuppressWarnings("unchecked")
		Map<String, Object> download = (Map<String, Object>) status.get("download");
		assertThat(download).isNotNull();
		assertThat(String.valueOf(download.get("downloadPath")))
				.isEqualTo("/api/hypit/exports/" + exportId + "/package");
		assertThat(String.valueOf(download.get("sha256"))).isEqualTo(ZIP_SHA);
		assertThat(((Number) download.get("sizeBytes")).longValue()).isEqualTo(realZipLength());

		// /package：真实 zip 字节流——JDK ZipInputStream 可解出 manifest，hash 对上。
		SIDECAR.stubFor(com.github.tomakehurst.wiremock.client.WireMock
				.get(urlEqualTo("/internal/v1/package-transfers/" + exportId + "/content"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/zip")
						.withHeader("ETag", "\"" + ZIP_SHA + "\"").withBody(realZip())));
		HypitPackageTransferService.BrokerStream stream = transfers.download(OWNER_A, exportId, null)
				.block(Duration.ofSeconds(20));
		assertThat(stream).isNotNull();
		assertThat(stream.status()).isEqualTo(200);
		assertThat(stream.headers().get("ETag")).isEqualTo("\"" + ZIP_SHA + "\"");
		byte[] zipBytes = DataBufferUtils.join(stream.body()).map(buffer -> {
			byte[] data = new byte[buffer.readableByteCount()];
			buffer.read(data);
			DataBufferUtils.release(buffer);
			return data;
		}).block(Duration.ofSeconds(20));
		assertThat(zipBytes).isNotNull();
		assertThat(zipBytes[0]).isEqualTo((byte) 'P');
		assertThat(zipBytes[1]).isEqualTo((byte) 'K');
		List<String> names = new java.util.ArrayList<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				names.add(entry.getName());
			}
		}
		assertThat(names).containsExactlyInAnyOrder("hypit-project.json", "main.svrun");
	}

	// ── TC-F2-30-02：下载的 zip 经上传导入 → 新 owner 工程 ready ─────────────────
	@Test
	@DisplayName("TC-F2-30-02 上传导入：新 owner 工程 ready、可打开")
	void uploadedZipBecomesReadyProjectForOwner() {
		stubImportSuccess(2L);
		Map<String, Object> result = packages.importTransfer(OWNER_B, UUID.randomUUID(),
				HypitPackageTransferService.transferIdFor(UUID.randomUUID()).toString(), "e".repeat(64), "B 的新工程")
				.block(Duration.ofSeconds(20));
		assertThat(result).isNotNull();
		UUID projectId = UUID.fromString(String.valueOf(result.get("projectId")));
		String status = db.sql("SELECT status FROM hypit_project WHERE id = CAST(:id AS uuid)")
				.bind("id", projectId.toString()).map((row, meta) -> row.get("status", String.class)).one()
				.block(Duration.ofSeconds(10));
		String owner = db.sql("SELECT account_id FROM hypit_project WHERE id = CAST(:id AS uuid)")
				.bind("id", projectId.toString()).map((row, meta) -> row.get("account_id", String.class)).one()
				.block(Duration.ofSeconds(10));
		Long revision = db.sql("SELECT revision FROM hypit_project WHERE id = CAST(:id AS uuid)")
				.bind("id", projectId.toString()).map((row, meta) -> row.get("revision", Long.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(status).isEqualTo("ready");
		assertThat(owner).isEqualTo(OWNER_B);
		assertThat(revision).isEqualTo(2L);
	}

	// ── TC-F2-30-03：A 的 exportId，B/匿名 404 无字节 ────────────────────────────
	@Test
	@DisplayName("TC-F2-30-03 他人访问导出：状态与取流都 404，无字节")
	void otherOwnerGets404WithoutBytes() {
		stubExportSuccess(1L);
		Map<String, Object> accepted = packages
				.export(OWNER_A, UUID.randomUUID(), UUID.randomUUID(), "导出", null, "main.svrun")
				.block(Duration.ofSeconds(20));
		UUID exportId = UUID.fromString(String.valueOf(accepted.get("exportId")));
		SIDECAR.stubFor(com.github.tomakehurst.wiremock.client.WireMock
				.get(urlEqualTo("/internal/v1/package-transfers/" + exportId + "/content")).willReturn(
						aResponse().withStatus(200).withHeader("Content-Type", "application/zip").withBody(realZip())));
		try {
			transfers.exportStatus(OWNER_B, exportId).block(Duration.ofSeconds(20));
			throw new AssertionError("expected 404");
		} catch (IntelligenceException error) {
			assertThat(error.code()).isEqualTo("hypit_not_found");
		}
		try {
			transfers.download(OWNER_B, exportId, null).flatMap(stream -> stream.body().hasElements())
					.block(Duration.ofSeconds(20));
			throw new AssertionError("expected 404");
		} catch (IntelligenceException error) {
			assertThat(error.code()).isEqualTo("hypit_not_found");
		}
		// B 的 404 不触发内部取流（零字节离开 broker）。
		SIDECAR.verify(0, com.github.tomakehurst.wiremock.client.WireMock
				.getRequestedFor(urlEqualTo("/internal/v1/package-transfers/" + exportId + "/content")));
	}

	// ── TC-F2-30-04：上传中断无假工程；同 requestId 重试收敛；Range 透传 ─────────
	@Test
	@DisplayName("TC-F2-30-04 上传中断无 ready 假工程；重试同 requestId 收敛；Range 透传")
	void interruptedUploadLeavesNoFakeProjectAndRetryConverges() {
		// 第一次上传转发失败（broker 5xx）→ 上传断、导入不入库（无 ready 假工程）。
		SIDECAR.stubFor(put(urlPathEqualTo(
				"/internal/v1/package-transfers/" + HypitPackageTransferService.transferIdFor(REQUEST_ID) + "/content"))
				.willReturn(aResponse().withStatus(503).withBody("broker staging unavailable")));
		try {
			transfers.uploadAndImport(OWNER_B, REQUEST_ID, memoryFilePart(realZip()), null)
					.block(Duration.ofSeconds(20));
			throw new AssertionError("expected failure");
		} catch (IntelligenceException error) {
			assertThat(error.code()).isEqualTo("hypit_backend_unavailable");
		}
		Long projects = db.sql("SELECT count(*) AS n FROM hypit_project WHERE account_id = :o").bind("o", OWNER_B)
				.map((row, meta) -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
		assertThat(projects).as("no fake ready project after interrupted upload").isZero();

		// broker 恢复：同一 requestId（稳定 transferId）重试 → 收敛同一导入。
		// C107F2-37（缺陷 K 回归）：转发体必须与原 zip 字节相同——此前 doOnNext
		// 消费读位置未还原，PUT 体恒 0 字节（EOCD 必败），本断言锁死该回归。
		SIDECAR.resetAll();
		stubImportSuccess(2L);
		SIDECAR.stubFor(put(urlPathEqualTo(
				"/internal/v1/package-transfers/" + HypitPackageTransferService.transferIdFor(REQUEST_ID) + "/content"))
				.withRequestBody(binaryEqualTo(realZip()))
				.willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
						.withBody("{\"transferId\":\"x\",\"sha256\":\"" + "e".repeat(64) + "\",\"sizeBytes\":1024}")));
		Map<String, Object> retried = transfers.uploadAndImport(OWNER_B, REQUEST_ID, memoryFilePart(realZip()), null)
				.block(Duration.ofSeconds(30));
		assertThat(retried).isNotNull();
		String status = db.sql("SELECT status FROM hypit_project WHERE account_id = :o").bind("o", OWNER_B)
				.map((row, meta) -> row.get("status", String.class)).one().block(Duration.ofSeconds(10));
		assertThat(status).isEqualTo("ready");

		// Range 头原样透传给 broker（206/416 语义由 broker 按 §6.13 产出）。
		UUID exportId = UUID.fromString(String.valueOf(retried.get("projectId")));
		stubExportSuccess(2L);
		Map<String, Object> exported = packages.export(OWNER_B, exportId, UUID.randomUUID(), "导出", null, "main.svrun")
				.block(Duration.ofSeconds(20));
		UUID downloadId = UUID.fromString(String.valueOf(exported.get("exportId")));
		SIDECAR.stubFor(com.github.tomakehurst.wiremock.client.WireMock
				.get(urlEqualTo("/internal/v1/package-transfers/" + downloadId + "/content"))
				.willReturn(aResponse().withStatus(206).withHeader("Content-Type", "application/zip")
						.withHeader("Content-Range", "bytes 0-99/" + realZipLength())
						.withBody(java.util.Arrays.copyOf(realZip(), 100))));
		HypitPackageTransferService.BrokerStream ranged = transfers.download(OWNER_B, downloadId, "bytes=0-99")
				.block(Duration.ofSeconds(20));
		assertThat(ranged).isNotNull();
		assertThat(ranged.status()).isEqualTo(206);
		assertThat(ranged.headers().get("Content-Range")).startsWith("bytes 0-99/");
		SIDECAR.verify(com.github.tomakehurst.wiremock.client.WireMock
				.getRequestedFor(urlEqualTo("/internal/v1/package-transfers/" + downloadId + "/content"))
				.withHeader("Range", com.github.tomakehurst.wiremock.client.WireMock.equalTo("bytes=0-99")));
	}

	private static final UUID REQUEST_ID = UUID.fromString("99999999-9999-4999-8999-999999999999");

	/** 内存 FilePart（multipart 解析已在 WebFlux 层；这里驱动上传转发链）。 */
	private static org.springframework.http.codec.multipart.FilePart memoryFilePart(byte[] bytes) {
		org.springframework.http.codec.multipart.FilePart part = org.mockito.Mockito
				.mock(org.springframework.http.codec.multipart.FilePart.class);
		org.mockito.Mockito.when(part.name()).thenReturn("file");
		org.mockito.Mockito.when(part.filename()).thenReturn("project.zip");
		org.mockito.Mockito.when(part.headers()).thenReturn(new org.springframework.http.HttpHeaders());
		org.mockito.Mockito.when(part.content())
				.thenReturn(Flux.just(new org.springframework.core.io.buffer.DefaultDataBufferFactory().wrap(bytes)));
		return part;
	}
}
