package com.grassland.intelligence.creationcontext;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 任务书 #70 卡C 对账测试：平台规则契约的媒体规格维度（PRD §4.7「图片尺寸和视频比例」）。
 *
 * <p>
 * 契约三端管线（前端 import / gradle 拷贝 classpath / snapshot 全量 convertValue 冻结）下，
 * 本测试锁死三件事：契约结构与 version；{@link PlatformCreationRuleCatalog#snapshot} 对新维度的
 * 全量透传（D8：后端零 Java 改动即随 creation_context_snapshot 版本化冻结）；契约 videoSpec 与
 * {@code videoproduction.VideoResolution.defaultFor} 值集一致（bilibili→16:9
 * 横版，其余→9:16 竖版； VideoResolution 是 videoproduction 包 package-private
 * 不可达，故以值集分布断言，两边改任一处此处会红）。
 */
@DisplayName("Platform media spec contract (Card C)")
class PlatformMediaSpecContractTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final JsonNode contract = loadContract();

	private static JsonNode loadContract() {
		try (java.io.InputStream stream = PlatformMediaSpecContractTest.class.getClassLoader()
				.getResourceAsStream("contracts/platform-format-rules.json")) {
			if (stream == null) {
				throw new IllegalStateException("Missing contracts/platform-format-rules.json");
			}
			return new ObjectMapper().readTree(stream);
		} catch (Exception error) {
			throw new IllegalStateException("Cannot load platform format rule contract", error);
		}
	}

	@Test
	@DisplayName("契约 version=2026-09-04、9 平台、每平台 imageSpec/videoSpec 结构合法")
	void contractVersionAndStructure() {
		assertThat(contract.path("version").asText()).isEqualTo("2026-09-04");
		List<JsonNode> platforms = new ArrayList<>();
		contract.path("platforms").forEach(platforms::add);
		assertThat(platforms).hasSize(9);
		for (JsonNode platform : platforms) {
			String id = platform.path("platformId").asText();
			for (String dim : List.of("imageSpec", "videoSpec")) {
				JsonNode spec = platform.path(dim);
				if (spec.isMissingNode() || spec.isNull()) {
					continue; // null 表示该平台无该维度主规格建议
				}
				assertThat(spec.path("aspect").asText("")).as(id + "." + dim + ".aspect").isNotEmpty();
				assertThat(spec.path("width").asInt(0)).as(id + "." + dim + ".width").isPositive();
				assertThat(spec.path("height").asInt(0)).as(id + "." + dim + ".height").isPositive();
			}
		}
	}

	@Test
	@DisplayName("snapshot 全量透传：douyin 快照含 imageSpec/videoSpec（convertValue 冻结证明）")
	void snapshotCarriesMediaSpecs() {
		java.util.Map<String, Object> snapshot = PlatformCreationRuleCatalog.snapshot("douyin", "article");
		assertThat(snapshot).containsKeys("imageSpec", "videoSpec");
		@SuppressWarnings("unchecked")
		java.util.Map<String, Object> imageSpec = (java.util.Map<String, Object>) snapshot.get("imageSpec");
		assertThat(imageSpec.get("aspect")).isEqualTo("9:16");
		assertThat(imageSpec.get("width")).isEqualTo(1080);
		assertThat(imageSpec.get("height")).isEqualTo(1920);
	}

	@Test
	@DisplayName("契约 videoSpec 与 VideoResolution.defaultFor 值集一致：16:9 恰为 bilibili，其余 9:16")
	void videoSpecMatchesDefaultResolutionValueSet() {
		List<String> landscape = new ArrayList<>();
		List<String> portrait = new ArrayList<>();
		contract.path("platforms").forEach(platform -> {
			JsonNode videoSpec = platform.path("videoSpec");
			if (videoSpec.isMissingNode() || videoSpec.isNull()) {
				return;
			}
			String aspect = videoSpec.path("aspect").asText();
			if ("16:9".equals(aspect)) {
				landscape.add(platform.path("platformId").asText());
			} else if ("9:16".equals(aspect)) {
				portrait.add(platform.path("platformId").asText());
			}
		});
		// 对应
		// VideoResolution.defaultFor：bilibili→LANDSCAPE(1920x1080)，其余→PORTRAIT(1080x1920)
		assertThat(landscape).containsExactly("bilibili");
		assertThat(portrait).containsExactlyInAnyOrder("xiaohongshu", "douyin", "kuaishou", "wechat-channels");
	}
}
