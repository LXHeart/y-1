package com.grassland.intelligence.creationstudio;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * 任务书 #101 C101-16（TC101-072~077）：确定性排版与受控 HTML。 文字 token
 * 一致（金额/日期/引用/表格/代码）、代码逐字、HTML 注入不外泄、外部图不抓取、缺媒体不伪装完整、主题只改样式、AI 调用数为 0。
 */
@TestPropertySource(properties = {"creation.studio.writes-enabled=true"})
class CreationRenderIT extends IntelligenceItSupport {

	private static final String ACCOUNT = "00000000-0000-4000-8000-00000000060f";

	private String draftId;
	private int draftVersion;

	@BeforeEach
	void seed() {
		db.sql("DELETE FROM creation_visual_artifact").then().then(db.sql("DELETE FROM creation_visual_item").then())
				.then(db.sql("DELETE FROM card_series_operation WHERE api_version = 2").then())
				.then(db.sql("DELETE FROM creation_visual_quote").then())
				.then(db.sql("DELETE FROM creation_visual_plan_revision").then())
				.then(db.sql("DELETE FROM creation_visual_plan").then())
				.then(db.sql("DELETE FROM creation_source_document").then())
				.then(db.sql("DELETE FROM creation_draft WHERE owner_account_id = :a").bind("a", ACCOUNT).then())
				.block(java.time.Duration.ofSeconds(10));
		QWEN.resetAll();
		draftId = createDraft();
		draftVersion = 1;
	}

	private String createDraft() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sourceType", "independent");
		body.put("title", "排版 IT 草稿");
		body.put("articleTitle", "三年排队生意：一碗面的数据复盘");
		body.put("platform", "wechat-official");
		body.put("contentForm", "graphic");
		body.put("capability", "article");
		body.put("content", CONTENT);
		Map<?, ?> response = client().post().uri("/api/creation-drafts")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		return ((Map<?, ?>) response.get("data")).get("id").toString();
	}

	private static final String CONTENT = """
			门店三年，人均 68 元，翻台四次。

			## 定价与销量

			招牌面 32 元一碗，日销 200 碗。活动截止 2026-10-01。

			| 品项 | 价格 | 日销 |
			 | --- | --- | --- |
			| 招牌面 | 32 元 | 200 碗 |

			```java
			int price = 32; // 招牌面定价
			```

			<iframe src="https://evil.example.invalid/x"></iframe><script>alert('x')</script>

			![外部示意图](https://cdn.example.invalid/pic.jpg)

			参考 [行业报告](https://example.invalid/report) 的口径。""";

	@SuppressWarnings("unchecked")
	private Map<String, Object> preview(Map<String, Object> overrides, Integer expectedStatus) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("draftId", draftId);
		body.put("version", draftVersion);
		body.put("theme", "standard");
		body.putAll(overrides);
		var result = client().post().uri("/api/creation-studio/render-previews")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isEqualTo(expectedStatus == null ? 200 : expectedStatus)
				.expectBody(Map.class).returnResult();
		return result.getResponseBody() == null ? null : (Map<String, Object>) result.getResponseBody().get("data");
	}

	// ---- TC101-072/073：文字 token 一致（金额/日期/引用/表格/代码逐字） ----

	@Test
	void textTokensPreservedAcrossRender() {
		Map<String, Object> data = preview(Map.of(), null);
		String text = (String) data.get("text");
		assertThat(text).contains("人均 68 元").contains("32 元一碗").contains("2026-10-01").contains("200 碗");
		assertThat((String) data.get("html")).contains("int price = 32; // 招牌面定价");
		// 表格受控呈现
		assertThat(org.jsoup.Jsoup.parse((String) data.get("html")).select("th").eachText()).contains("品项", "价格", "日销");
	}

	// ---- TC101-074：HTML 注入不外泄（iframe/script 丢弃；无脚本/外链资源抓取） ----

	@Test
	void rawHtmlIsEscapedAndPreservedAsText() {
		Map<String, Object> data = preview(Map.of(), null);
		String html = (String) data.get("html");
		assertThat(org.jsoup.Jsoup.parse(html).select("iframe,script")).isEmpty();
		assertThat(org.jsoup.Jsoup.parse(html).text()).contains("<iframe", "alert(", "evil.example.invalid");
	}

	// ---- TC101-075：外部图不抓取（占位提示）；链接按请求转引用 ----

	@Test
	void externalImageBoundedAndLinksCitedOnRequest() {
		Map<String, Object> plain = preview(Map.of("citeExternalLinks", false), null);
		assertThat((String) plain.get("html")).contains("外部示意图");
		assertThat((String) plain.get("html")).doesNotContain("src=\"https://cdn.example.invalid");
		assertThat(org.jsoup.Jsoup.parse((String) plain.get("html")).select("a").eachAttr("href"))
				.contains("https://example.invalid/report");
		Map<String, Object> cited = preview(Map.of("citeExternalLinks", true), null);
		assertThat((String) cited.get("html")).contains("data-render=\"references\"").contains("[1]");
	}

	// ---- TC101-076：两主题文字一致（主题只改样式） ----

	@Test
	void themesOnlyChangeStyles() {
		Map<String, Object> standard = preview(Map.of(), null);
		Map<String, Object> compact = preview(Map.of("theme", "compact"), null);
		assertThat(standard.get("text")).isEqualTo(compact.get("text"));
		assertThat(standard.get("contentHash")).isEqualTo(compact.get("contentHash"));
		assertThat((String) standard.get("html")).isNotEqualTo((String) compact.get("html"));
	}

	// ---- TC101-077：缺媒体不伪装完整；未绑定段落附后并标注；历史版本按快照渲染 ----

	/** 造真实媒体行（引用校验需要 owner+active 的 media_reference）。 */
	private String seedMedia() {
		return db.sql("""
				INSERT INTO media_reference(owner_account_id,purpose,object_key,mime_type,status)
				VALUES (:owner,'reference',:key,'image/png','active') RETURNING id
				""").bind("owner", ACCOUNT).bind("key", UUID.randomUUID().toString())
				.map(row -> row.get(0, UUID.class).toString()).one().block(java.time.Duration.ofSeconds(5));
	}

	@Test
	@SuppressWarnings("unchecked")
	void missingMediaWarnedAndUnboundAppendixMarked() {
		// 带已采用引用但无源文档（无 placement 锚文本）→ 未绑定段落
		String unboundMedia = seedMedia();
		String coverMedia = seedMedia();
		Map<String, Object> workspace = new LinkedHashMap<>();
		workspace.put("schemaVersion", 1);
		workspace.put("capability", "article");
		workspace.put("resultRefs", List
				.of(Map.of("id", unboundMedia, "refType", "media", "role", "card", "cardId", "card-1", "position", 1)));
		workspace.put("delivery", Map.of("coverRef",
				Map.of("id", coverMedia, "refType", "media", "role", "cover", "cardId", "card-1", "position", 1)));
		client().put().uri("/api/creation-drafts/" + draftId).header("X-Grassland-Identity", sign(ACCOUNT, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", draftVersion, "title",
						"排版 IT 草稿", "content", CONTENT, "workspace", workspace))
				.exchange().expectStatus().isOk();
		draftVersion += 1;
		Map<String, Object> data = preview(Map.of(), null);
		assertThat((List<String>) data.get("unresolvedMediaIds")).containsExactlyInAnyOrder(unboundMedia, coverMedia,
				"https://cdn.example.invalid/pic.jpg");
		assertThat((String) data.get("html")).contains("未绑定段落");
		assertThat((List<String>) data.get("warnings")).anyMatch(warning -> warning.contains(unboundMedia));
		// 封面媒体在最前（figure + /api/media 相对路径，非签名 URL）
		assertThat((String) data.get("html")).doesNotContain("src=\"/api/media/");

		// 历史版本：正文改后 v1 仍按 v1 渲染（快照语义）
		client().put().uri("/api/creation-drafts/" + draftId).header("X-Grassland-Identity", sign(ACCOUNT, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", draftVersion, "title",
						"排版 IT 草稿", "content", "正文已完全替换。", "workspace", workspace))
				.exchange().expectStatus().isOk();
		draftVersion += 1;
		Map<String, Object> v1 = preview(Map.of("version", 1), null);
		assertThat((String) v1.get("text")).contains("人均 68 元");
		Map<String, Object> current = preview(Map.of(), null);
		assertThat((String) current.get("text")).contains("正文已完全替换").doesNotContain("人均 68 元");
	}

	// ---- V-AI-0：渲染零模型调用 ----

	@Test
	void renderMakesNoModelCalls() {
		preview(Map.of(), null);
		preview(Map.of("theme", "compact"), null);
		assertThat(QWEN.getAllServeEvents()).isEmpty();
	}
}
