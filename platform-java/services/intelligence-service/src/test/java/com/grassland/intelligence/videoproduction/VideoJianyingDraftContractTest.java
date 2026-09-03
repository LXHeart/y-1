package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.videoproduction.export.JianyingDraftBuilder;
import com.grassland.intelligence.videoproduction.export.JianyingDraftBuilder.DraftBuild;
import com.grassland.intelligence.videoproduction.export.JianyingDraftBuilder.ShotInput;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 剪映草稿契约自检（任务书 #69 卡A）：builder 是纯构造，不起容器直测产物—— 三轨结构 / 素材相对路径布局 / 微秒时间轴首尾相接 /
 * version 与支持区间主版本配对 / draft_meta_info.json 必填键齐备；另锁降级形态（缺旁白/缺成片）的结构仍合法。
 * 只验不改：生成逻辑与字段由本测试锁定，漂移即红。
 */
@DisplayName("Jianying draft contract (任务书 #69 卡A)")
class VideoJianyingDraftContractTest {

	private static final String DRAFT_ID = "0a1b2c3d-0000-4000-8000-0123456789ab";
	private static final String DRAFT_NAME = "caochang-video-0a1b2c3d";

	/** 素材副本键 = 草稿根相对路径（materials/<folder>/shot-<seq>.<ext>，无前导斜杠）。 */
	private static final Pattern REL_MATERIAL_KEY = Pattern
			.compile("^materials/(video|audio)/shot-\\d+\\.(mp3|mp4|wav)$");

	private final ObjectMapper mapper = new ObjectMapper();
	private final JianyingDraftBuilder builder = new JianyingDraftBuilder();

	@Test
	@DisplayName("最小合法形态：三轨存在且类型正确，素材副本键为草稿根相对路径")
	void threeTracksAndRelativeMaterialKeys() throws Exception {
		DraftBuild draft = builder.build(DRAFT_ID, DRAFT_NAME, false, fullShots());

		JsonNode content = mapper.readTree(draft.draftContentJson());
		List<String> trackTypes = new java.util.ArrayList<>();
		content.path("tracks").forEach(track -> trackTypes.add(track.path("type").asText()));
		assertThat(trackTypes).containsExactly("video", "audio", "text");

		assertThat(draft.materials().keySet()).isNotEmpty();
		for (String key : draft.materials().keySet()) {
			assertThat(key).matches(REL_MATERIAL_KEY);
		}
		assertThat(draft.materials().keySet()).containsExactlyInAnyOrder("materials/video/shot-1.mp4",
				"materials/video/shot-2.mp4", "materials/audio/shot-1.wav", "materials/audio/shot-2.wav");
	}

	@Test
	@DisplayName("素材引用路径：占位前缀 + 草稿根相对后缀，与副本键一一对应")
	void materialPathsCarryDraftRelativeSuffix() throws Exception {
		DraftBuild draft = builder.build(DRAFT_ID, DRAFT_NAME, false, fullShots());
		JsonNode content = mapper.readTree(draft.draftContentJson());

		String placeholderRoot = "/JianYingDrafts/" + DRAFT_NAME + "/";
		for (JsonNode material : content.path("materials").path("material_videos")) {
			String path = material.path("path").asText();
			assertThat(path).startsWith(placeholderRoot);
			assertThat(path).endsWith(relKeyOf(path));
			assertThat(draft.materials()).containsKey(relKeyOf(path));
		}
		for (JsonNode material : content.path("materials").path("material_audios")) {
			String path = material.path("path").asText();
			assertThat(path).startsWith(placeholderRoot);
			assertThat(path).endsWith(relKeyOf(path));
			assertThat(draft.materials()).containsKey(relKeyOf(path));
		}
	}

	private static String relKeyOf(String path) {
		return path.substring(path.lastIndexOf("/materials/") + 1);
	}

	@Test
	@DisplayName("微秒时间轴：每轨素材区间首尾相接无重叠无空洞，末尾=任务实际时长")
	void microsecondTimelineIsContiguous() throws Exception {
		DraftBuild draft = builder.build(DRAFT_ID, DRAFT_NAME, false, fullShots());
		JsonNode content = mapper.readTree(draft.draftContentJson());

		long expectedTotalUs = (5_000 + 4_000) * 1_000L;
		assertThat(content.path("duration").asLong()).isEqualTo(expectedTotalUs);
		for (JsonNode track : content.path("tracks")) {
			long cursor = 0;
			for (JsonNode segment : track.path("segments")) {
				JsonNode timerange = segment.path("target_timerange");
				assertThat(timerange.path("start").asLong()).isEqualTo(cursor);
				cursor += timerange.path("duration").asLong();
			}
			assertThat(cursor).isEqualTo(expectedTotalUs);
		}
	}

	@Test
	@DisplayName("version 字段与支持区间主版本配对（6.x → 360000 档）")
	void versionFieldPairsWithSupportedRangeMajor() throws Exception {
		DraftBuild draft = builder.build(DRAFT_ID, DRAFT_NAME, false, fullShots());
		JsonNode content = mapper.readTree(draft.draftContentJson());

		int major = Integer
				.parseInt(JianyingDraftBuilder.SUPPORTED_JIANYING_RANGE.replaceAll(".*?(\\d+)\\.\\d+.*", "$1"));
		assertThat(major).isEqualTo(6);
		assertThat(content.path("version").asInt()).isEqualTo(360000);
		assertThat(JianyingDraftBuilder.supportedRange()).isEqualTo(JianyingDraftBuilder.SUPPORTED_JIANYING_RANGE);
	}

	@Test
	@DisplayName("draft_meta_info.json 必填键齐备（以 builder 现产物为准逐键断言）")
	void draftMetaInfoKeysComplete() throws Exception {
		DraftBuild draft = builder.build(DRAFT_ID, DRAFT_NAME, false, fullShots());
		JsonNode meta = mapper.readTree(draft.draftMetaJson());

		List<String> metaKeys = new java.util.ArrayList<>();
		meta.fieldNames().forEachRemaining(metaKeys::add);
		assertThat(metaKeys).containsExactlyInAnyOrder("draft_cloud_last_action_download", "draft_cloud_purchase_info",
				"draft_cloud_template_id", "draft_cover", "draft_deeplink_url", "draft_fold_path", "draft_id",
				"draft_is_ai_shorts", "draft_is_article_video_draft", "draft_is_from_deeplink", "draft_materials",
				"draft_micro_se", "draft_name", "draft_new_version", "draft_removable_storage_device",
				"draft_root_path", "draft_timeline_materials_size", "draft_type", "tm_draft_create",
				"tm_draft_modified", "tm_duration");
		assertThat(meta.path("draft_id").asText()).isEqualTo(DRAFT_ID);
		assertThat(meta.path("draft_name").asText()).isEqualTo(DRAFT_NAME);
		assertThat(meta.path("tm_duration").asLong()).isEqualTo(9_000_000L);
	}

	@Test
	@DisplayName("降级形态：缺旁白（audio/narration 均空）→ 仅视频轨，时间轴仍相接")
	void degradedShotWithoutNarrationKeepsVideoTrackOnly() throws Exception {
		List<ShotInput> shots = List.of(new ShotInput(1, 5_000, bytes("mp4"), null, null),
				new ShotInput(2, 4_000, bytes("mp4"), null, "  "));
		DraftBuild draft = builder.build(DRAFT_ID, DRAFT_NAME, true, shots);
		JsonNode content = mapper.readTree(draft.draftContentJson());

		List<String> trackTypes = new java.util.ArrayList<>();
		content.path("tracks").forEach(track -> trackTypes.add(track.path("type").asText()));
		assertThat(trackTypes).containsExactly("video");
		assertThat(content.path("materials").path("material_audios")).isEmpty();
		assertThat(content.path("materials").path("material_texts")).isEmpty();
		assertThat(draft.materials().keySet()).containsExactlyInAnyOrder("materials/video/shot-1.mp4",
				"materials/video/shot-2.mp4");
		assertThat(content.path("duration").asLong()).isEqualTo(9_000_000L);
		// 横版画布随 landscape 入档
		assertThat(content.path("canvas_config").path("ratio").asText()).isEqualTo("16:9");
	}

	@Test
	@DisplayName("降级形态：缺成片段字节 → 视频轨仍在（引用占位），副本缺省但结构合法")
	void degradedShotWithoutSegmentBytesKeepsStructure() throws Exception {
		List<ShotInput> shots = List.of(new ShotInput(1, 5_000, null, bytes("wav"), "旁白一"),
				new ShotInput(2, 4_000, bytes("mp4"), null, "旁白二"));
		DraftBuild draft = builder.build(DRAFT_ID, DRAFT_NAME, false, shots);
		JsonNode content = mapper.readTree(draft.draftContentJson());

		List<String> trackTypes = new java.util.ArrayList<>();
		content.path("tracks").forEach(track -> trackTypes.add(track.path("type").asText()));
		assertThat(trackTypes).containsExactly("video", "audio", "text");
		assertThat(draft.materials().keySet()).containsExactlyInAnyOrder("materials/video/shot-2.mp4",
				"materials/audio/shot-1.wav");
		assertThat(content.path("duration").asLong()).isEqualTo(9_000_000L);
	}

	private static List<ShotInput> fullShots() {
		return List.of(new ShotInput(1, 5_000, bytes("mp4"), bytes("wav"), "老王面馆现熬骨汤"),
				new ShotInput(2, 4_000, bytes("mp4"), bytes("wav"), "每天现切这碗面"));
	}

	private static byte[] bytes(String marker) {
		return marker.getBytes(java.nio.charset.StandardCharsets.UTF_8);
	}
}
