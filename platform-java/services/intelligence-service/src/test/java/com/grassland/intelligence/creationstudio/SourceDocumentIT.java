package com.grassland.intelligence.creationstudio;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.IntelligenceItSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-02（API101-03/04、V77）：来源文档导入、归属、边界与无网络访问。
 * TC101-005～010。fixture：tests/fixtures/creation-studio/source-samples.json（全合成）。
 */
@TestPropertySource(properties = {"creation.studio.writes-enabled=true"})
class SourceDocumentIT extends IntelligenceItSupport {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Autowired
	private CreationStudioProperties studioProperties;

	private JsonNode samples;

	@BeforeEach
	void loadSamplesAndClean() {
		// Gradle 测试工作目录 = 模块目录；fixture 在仓库根 tests/fixtures（C101-02 不改 gradle 资源拷贝）。
		Path fixture = Path.of("..", "..", "..", "tests", "fixtures", "creation-studio", "source-samples.json");
		try {
			samples = MAPPER.readTree(Files.readString(fixture));
		} catch (Exception error) {
			throw new IllegalStateException("缺少来源样稿 fixture: " + fixture.toAbsolutePath(), error);
		}
		// 本类断言「导入纯计算零上游请求」；共享 QWEN journal 先清空，避免同 JVM 前序类
		// （TextProposalIT/VisualPlanIT 等）的残留请求让校验依赖类执行顺序（C101-05 实录）。
		QWEN.resetAll();
		db.sql("DELETE FROM creation_source_document").then().block(java.time.Duration.ofSeconds(10));
		db.sql("DELETE FROM creation_draft WHERE owner_account_id LIKE 'src-it-%'").then()
				.block(java.time.Duration.ofSeconds(10));
	}

	private String createDraft(String account, String content) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sourceType", "independent");
		body.put("title", "来源 IT 草稿");
		body.put("platform", "xiaohongshu");
		body.put("contentForm", "graphic");
		body.put("capability", "article");
		if (content != null) {
			body.put("content", content);
		}
		Map<String, Object> response = client().post().uri("/api/creation-drafts")
				.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		return ((Map<?, ?>) response.get("data")).get("id").toString();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> importSource(String account, String draftId, int expectedVersion, String kind,
			String text) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", UUID.randomUUID().toString());
		body.put("draftId", draftId);
		body.put("expectedDraftVersion", expectedVersion);
		body.put("kind", kind);
		if (text != null) {
			body.put("text", text);
		}
		Map<String, Object> response = client().post().uri("/api/creation-studio/sources")
				.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isCreated().expectBody(Map.class).returnResult()
				.getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> readSource(String account, String id) {
		Map<String, Object> response = client().get().uri("/api/creation-studio/sources/" + id)
				.header("X-Grassland-Identity", sign(account, null)).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	/** TC101-005：中文／emoji／重复段落导入再读取——原文、hash、块跨度正确；ID 互异。 */
	@Test
	void chineseDuplicateAndEmojiImportRoundTripsWithBlockSpans() {
		String draftId = createDraft("src-it-a", null);
		String text = samples.get("samples").get("duplicateParagraphs").asText() + "\n\n含 emoji 的段落 🍜🧋 与金额 ¥32.5。";
		Map<String, Object> data = importSource("src-it-a", draftId, 1, "markdown", text);

		assertThat(data.get("rawText")).isEqualTo(text);
		assertThat((String) data.get("contentHash")).matches("[0-9a-f]{64}");
		List<Map<String, Object>> blocks = (List<Map<String, Object>>) data.get("blocks");
		assertThat(blocks).hasSize(4); // 三段重复/第二段 + emoji 段（重复段落是两个独立块）
		assertThat(blocks.get(0).get("id")).isNotEqualTo(blocks.get(1).get("id"));
		assertThat(blocks.get(0).get("text")).isEqualTo(blocks.get(1).get("text")); // 同文不同 ID
		assertThat(blocks.get(0).get("kind")).isEqualTo("paragraph");

		// 跨度可切回 normalizedMarkdown（markdown 分支 normalized == LF 原文）
		String normalized = (String) data.get("normalizedMarkdown");
		for (Map<String, Object> block : blocks) {
			int start = (Integer) block.get("startCodePoint");
			int end = (Integer) block.get("endCodePoint");
			assertThat(normalized.codePoints().limit(end).count()).isEqualTo(end);
			String sliced = sliceByCodePoints(normalized, start, end);
			assertThat(sliced).isEqualTo(block.get("text"));
		}

		// 读取（GET）与导入返回一致
		Map<String, Object> reread = readSource("src-it-a", data.get("id").toString());
		assertThat(reread.get("contentHash")).isEqualTo(data.get("contentHash"));
		assertThat(reread.get("blocks")).isEqualTo(blocks);
	}

	private static String sliceByCodePoints(String value, int start, int end) {
		StringBuilder sliced = new StringBuilder();
		int index = 0;
		for (int i = 0; i < value.length() && index < end;) {
			int cp = value.codePointAt(i);
			if (index >= start) {
				sliced.appendCodePoint(cp);
			}
			i += Character.charCount(cp);
			index++;
		}
		return sliced.toString();
	}

	/** TC101-005 续：markdown 富文本块类型（标题/列表/表格/代码/引用）与纯文本字面呈现。 */
	@Test
	void markdownStructureMapsToBlockKinds() {
		String draftId = createDraft("src-it-a", null);
		Map<String, Object> data = importSource("src-it-a", draftId, 1, "markdown",
				samples.get("samples").get("markdownRich").asText());
		List<String> kinds = ((List<Map<String, Object>>) data.get("blocks")).stream()
				.map(b -> b.get("kind").toString()).toList();
		assertThat(kinds).containsExactly("heading", "paragraph", "heading", "list", "table", "quote", "code",
				"paragraph");
		// 嵌套列表属于一个顶层块
		assertThat(kinds.stream().filter("list"::equals).count()).isEqualTo(1);
	}

	@Test
	void plainTextEscapesMarkdownControls() {
		String draftId = createDraft("src-it-a", null);
		String text = "1. 不是列表\n# 不是标题\n*不是强调*";
		Map<String, Object> data = importSource("src-it-a", draftId, 1, "plain-text", text);
		List<Map<String, Object>> blocks = (List<Map<String, Object>>) data.get("blocks");
		// 纯文本不做结构化：全部段落，且字面文本保留（转义不改变解码后内容）
		assertThat(blocks).allSatisfy(block -> assertThat(block.get("kind")).isEqualTo("paragraph"));
		assertThat(data.get("rawText")).isEqualTo(text);
		String normalized = (String) data.get("normalizedMarkdown");
		assertThat(normalized).contains("\\# 不是标题");
	}

	/** TC101-006：0／1／30000／30001 code point 与 UTF-8 字节边界。 */
	@Test
	void codePointAndByteBoundaries() {
		String draftId = createDraft("src-it-a", null);
		// 空与空白拒绝
		postSource("src-it-a", draftId, "markdown", "").expectStatus().is4xxClientError();
		postSource("src-it-a", draftId, "markdown", samples.get("samples").get("whitespaceOnly").asText())
				.expectStatus().is4xxClientError();
		// 1 code point 合法
		importSource("src-it-a", draftId, 1, "markdown", "好");
		// 30000 合法（程序化构造）
		importSource("src-it-a", draftId, 1, "markdown", "字".repeat(30_000));
		// 30001 拒绝
		postSource("src-it-a", draftId, "markdown", "字".repeat(30_001)).expectStatus().is4xxClientError();
		// UTF-8 > 128KiB 拒绝（128KiB = 131072 字节；每汉字 3 字节，43000 字 ≈ 129000 字节）
		postSource("src-it-a", draftId, "markdown", "字".repeat(43_100)).expectStatus().is4xxClientError();
	}

	/** 30k code point 全文在响应里出现约三份（raw/normalized/blocks）——本类单独放宽客户端解码缓冲。 */
	protected WebTestClient client() {
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
				.responseTimeout(java.time.Duration.ofSeconds(30))
				.codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(8 * 1024 * 1024)).build();
	}

	/** TC101-007：同 requestId 并发同文与异文——一份 source；异参 409。 */
	@Test
	void sameRequestKeyIsIdempotentAndConflictingInputRejected() {
		String draftId = createDraft("src-it-a", null);
		String requestId = UUID.randomUUID().toString();
		Map<String, Object> first = postSourceBody("src-it-a", draftId, "markdown", "第一份内容", requestId).expectStatus()
				.isCreated().expectBody(Map.class).returnResult().getResponseBody();
		// 同键同文重放 200 + 同一 ID
		Map<String, Object> replay = postSourceBody("src-it-a", draftId, "markdown", "第一份内容", requestId).expectStatus()
				.isOk().expectBody(Map.class).returnResult().getResponseBody();
		assertThat(((Map<?, ?>) replay.get("data")).get("id")).isEqualTo(((Map<?, ?>) first.get("data")).get("id"));
		// 同键异文 409
		postSourceBody("src-it-a", draftId, "markdown", "另一份内容", requestId).expectStatus()
				.isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
		// 并发同键同文：只有一行
		String requestId2 = UUID.randomUUID().toString();
		Mono.zip(
				Mono.fromCallable(() -> client().post().uri("/api/creation-studio/sources")
						.header("X-Grassland-Identity", sign("src-it-a", null)).contentType(MediaType.APPLICATION_JSON)
						.bodyValue(requestBody(draftId, "markdown", "并发内容", requestId2)).exchange().expectStatus()
						.is2xxSuccessful().expectBody(Map.class).returnResult().getResponseBody()),
				Mono.fromCallable(() -> client().post().uri("/api/creation-studio/sources")
						.header("X-Grassland-Identity", sign("src-it-a", null)).contentType(MediaType.APPLICATION_JSON)
						.bodyValue(requestBody(draftId, "markdown", "并发内容", requestId2)).exchange().expectStatus()
						.is2xxSuccessful().expectBody(Map.class).returnResult().getResponseBody()))
				.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
				.block(java.time.Duration.ofSeconds(15));
		Long rows = db.sql("SELECT count(*) AS total FROM creation_source_document WHERE request_id = :requestId")
				.bind("requestId", requestId2).map(row -> row.get("total", Long.class)).one()
				.block(java.time.Duration.ofSeconds(10));
		assertThat(rows).isEqualTo(1);
	}

	/** TC101-008：B 读 A source / A 引用 B 草稿导入——404，无内容泄露。 */
	@Test
	void crossAccountAndCrossDraftAccessRejected() {
		String draftA = createDraft("src-it-a", null);
		String draftB = createDraft("src-it-b", null);
		Map<String, Object> data = importSource("src-it-a", draftA, 1, "markdown", "A 的原稿");
		client().get().uri("/api/creation-studio/sources/" + data.get("id"))
				.header("X-Grassland-Identity", sign("src-it-b", null)).exchange().expectStatus().isNotFound();
		// A 拿 B 的 draftId 导入 → 草稿 404（不泄露存在性）
		postSource("src-it-a", draftB, "markdown", "冒充").expectStatus().isNotFound();
	}

	/** TC101-009：markdown 含外链图、HTML、脚本——不发网络、不执行、原文可查。 */
	@Test
	void htmlAndRemoteImagesAreStoredVerbatimWithoutNetwork() {
		String draftId = createDraft("src-it-a", null);
		Map<String, Object> data = importSource("src-it-a", draftId, 1, "markdown",
				samples.get("samples").get("htmlAndScript").asText());
		assertThat((String) data.get("rawText")).contains("<script>alert('xss')</script>");
		assertThat(data.get("normalizedMarkdown").toString()).contains("https://example.invalid/logo.png");
		assertThat((List<String>) data.get("warnings")).anyMatch(warning -> warning.contains("HTML"));
		// 导入是纯计算：整个用例期间平台上游（WireMock）零请求
		QWEN.verify(0, anyRequestedFor(anyUrl()));
	}

	/** TC101-010：draft-content 指定过期版本 409／伪造 text 400／正文冻结自服务端。 */
	@Test
	void draftContentFreezesServerSideContentAtVersion() {
		String draftId = createDraft("src-it-a", "服务端第一版正文");
		// 伪造 text 拒绝
		Map<String, Object> forged = new LinkedHashMap<>();
		forged.put("requestId", UUID.randomUUID().toString());
		forged.put("draftId", draftId);
		forged.put("expectedDraftVersion", 1);
		forged.put("kind", "draft-content");
		forged.put("text", "浏览器自带的假正文");
		client().post().uri("/api/creation-studio/sources").header("X-Grassland-Identity", sign("src-it-a", null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(forged).exchange().expectStatus().is4xxClientError();
		// 正常冻结
		Map<String, Object> data = importSource("src-it-a", draftId, 1, "draft-content", null);
		assertThat(data.get("rawText")).isEqualTo("服务端第一版正文");
		// 草稿推进到 v2 后用过期版本导入 → 409
		client().put().uri("/api/creation-drafts/" + draftId).header("X-Grassland-Identity", sign("src-it-a", null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", 1, "title", "来源 IT 草稿", "content", "第二版正文")).exchange()
				.expectStatus().isOk();
		postSource("src-it-a", draftId, "draft-content", null).expectStatus()
				.isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
	}

	/** CRLF/CR 统一为 LF；控制字符保留（§5.1 不做 NFKC／空格删除）。 */
	@Test
	void lineEndingsNormalizedToLf() {
		String draftId = createDraft("src-it-a", null);
		Map<String, Object> data = importSource("src-it-a", draftId, 1, "markdown",
				samples.get("samples").get("crlfNormalization").asText());
		String normalized = (String) data.get("normalizedMarkdown");
		assertThat(normalized).doesNotContain("\r");
		assertThat(normalized).contains("第一行使用 CRLF。\n第二行也使用 CRLF。\n单独 CR 的第三行。");
		assertThat((String) data.get("rawText")).contains("\r");
	}

	/** 草稿保存路径的 studio 引用校验：合法 source 引用可保存；伪造他草稿引用拒绝（不能只校验 UUID 形状）。 */
	@Test
	@SuppressWarnings("unchecked")
	void draftSaveValidatesStudioSourceOwnership() {
		String draftA = createDraft("src-it-a", null);
		String draftB = createDraft("src-it-b", null);
		Map<String, Object> data = importSource("src-it-a", draftA, 1, "markdown", "A 的原稿");

		// 伪造引用到 B 的草稿（A 的 source + B 的 draft）→ 拒绝
		client().put().uri("/api/creation-drafts/" + draftB).header("X-Grassland-Identity", sign("src-it-b", null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", 1, "title", "来源 IT 草稿", "workspace",
						Map.of("schemaVersion", 1, "capability", "article", "inputs",
								Map.of("studio", studioRefs(data.get("id").toString())))))
				.exchange().expectStatus().is4xxClientError();

		// 合法保存：本人 + 本草稿
		client().put().uri("/api/creation-drafts/" + draftA).header("X-Grassland-Identity", sign("src-it-a", null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", 1, "title", "来源 IT 草稿", "workspace",
						Map.of("schemaVersion", 1, "capability", "article", "inputs",
								Map.of("studio", studioRefs(data.get("id").toString())))))
				.exchange().expectStatus().isOk();

		// 未知 schemaVersion 拒绝写入；未开放引用（visualPlan 等）拒绝
		client().put().uri("/api/creation-drafts/" + draftA).header("X-Grassland-Identity", sign("src-it-a", null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", 2, "title", "来源 IT 草稿", "workspace",
						Map.of("schemaVersion", 1, "capability", "article", "inputs",
								Map.of("studio", Map.of("schemaVersion", 2)))))
				.exchange().expectStatus().is4xxClientError();
		client().put().uri("/api/creation-drafts/" + draftA).header("X-Grassland-Identity", sign("src-it-a", null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", 2, "title", "来源 IT 草稿", "workspace",
						Map.of("schemaVersion", 1, "capability", "article", "inputs",
								Map.of("studio",
										withField(Map.of("schemaVersion", 1), "visualPlan",
												Map.of("id", UUID.randomUUID().toString(), "revision", 1))))))
				.exchange().expectStatus().is4xxClientError();
	}

	/** 写开关关闭：拒绝新来源（404 STUDIO_DISABLED），读取既有记录不受影响（§7.6）。 */
	@Test
	void writesDisabledRejectsNewSourcesButReadsContinue() {
		String draftId = createDraft("src-it-a", null);
		Map<String, Object> data = importSource("src-it-a", draftId, 1, "markdown", "开关关闭前导入");
		boolean original = studioProperties.isWritesEnabled();
		try {
			studioProperties.setWritesEnabled(false);
			postSource("src-it-a", draftId, "markdown", "开关关闭后导入").expectStatus().isNotFound();
			Map<String, Object> reread = readSource("src-it-a", data.get("id").toString());
			assertThat(reread.get("rawText")).isEqualTo("开关关闭前导入");
		} finally {
			studioProperties.setWritesEnabled(original);
		}
	}

	private static Map<String, Object> studioRefs(String sourceDocumentId) {
		Map<String, Object> refs = new LinkedHashMap<>();
		refs.put("schemaVersion", 1);
		refs.put("sourceDocumentId", sourceDocumentId);
		refs.put("renderTheme", "standard");
		return refs;
	}

	private static Map<String, Object> withField(Map<String, Object> base, String key, Object value) {
		Map<String, Object> merged = new LinkedHashMap<>(base);
		merged.put(key, value);
		return merged;
	}

	private Map<String, Object> requestBody(String draftId, String kind, String text, String requestId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", requestId);
		body.put("draftId", draftId);
		body.put("expectedDraftVersion", 1);
		body.put("kind", kind);
		if (text != null) {
			body.put("text", text);
		}
		return body;
	}

	private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec postSourceBody(String account,
			String draftId, String kind, String text, String requestId) {
		return client().post().uri("/api/creation-studio/sources").header("X-Grassland-Identity", sign(account, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody(draftId, kind, text, requestId))
				.exchange();
	}

	private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec postSource(String account,
			String draftId, String kind, String text) {
		return postSourceBody(account, draftId, kind, text, UUID.randomUUID().toString());
	}

	@Test
	void sourceReplaySurvivesDraftEditsAndWriteShutdown() {
		String owner = "src-it-replay", draftId = createDraft(owner, "原文 68 元");
		Map<String, Object> body = Map.of("requestId", UUID.randomUUID().toString(), "draftId", draftId,
				"expectedDraftVersion", 1, "kind", "draft-content");
		var first = client().post().uri("/api/creation-studio/sources")
				.header("X-Grassland-Identity", sign(owner, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isCreated().expectBody(Map.class).returnResult()
				.getResponseBody();
		Map<?, ?> source = (Map<?, ?>) first.get("data");
		assertThat(source.get("createdAt")).isNotNull();
		client().put().uri("/api/creation-drafts/" + draftId).header("X-Grassland-Identity", sign(owner, null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", 1, "title", "新导航名称", "content", "后来编辑")).exchange().expectStatus()
				.isOk();
		studioProperties.setWritesEnabled(false);
		try {
			var replay = client().post().uri("/api/creation-studio/sources")
					.header("X-Grassland-Identity", sign(owner, null)).contentType(MediaType.APPLICATION_JSON)
					.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
					.getResponseBody();
			assertThat(((Map<?, ?>) replay.get("data")).get("id")).isEqualTo(source.get("id"));
			assertThat(((Map<?, ?>) replay.get("data")).get("rawText")).isEqualTo("原文 68 元");
			var changed = new LinkedHashMap<>(body);
			changed.put("expectedDraftVersion", 2);
			client().post().uri("/api/creation-studio/sources").header("X-Grassland-Identity", sign(owner, null))
					.contentType(MediaType.APPLICATION_JSON).bodyValue(changed).exchange().expectStatus()
					.isEqualTo(409);
		} finally {
			studioProperties.setWritesEnabled(true);
		}
	}

}
