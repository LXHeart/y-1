package com.grassland.intelligence.videoproduction.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.videoproduction.VideoShot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 剪映草稿构建器（任务书 #66 卡B2，A 轨）：生成剪映桌面版 draft 目录最小集——
 * draft_content.json（视频段/旁白音频/字幕文本三轨）+ draft_meta_info.json + materials 媒体副本。
 *
 * <p>
 * 草稿格式是非公开契约（任务书风险拍板）：本类独立成「兼容层」，字段漂移只改这里； 时间轴单位为微秒（draft
 * 全库约定）。三轨最小集之外不做特效/转场等富字段（卡面边界）。 实测锁定版本区间见
 * {@link #SUPPORTED_JIANYING_RANGE}；实机打开验证记录见任务书卡B2 验收。
 */
@org.springframework.stereotype.Component
public class JianyingDraftBuilder {

	/**
	 * 实现期锁定区间（预期「剪映专业版 6.x」）。实机验证指引与登记表见
	 * {@code docs/附录-剪映草稿导出实机验证指引.md}——验证通过后在该文档表格回填 「剪映专业版 6.x.y（实测日期）+
	 * 结论」，区间收窄时同步本常量（任务书 #69 卡A）。
	 */
	public static final String SUPPORTED_JIANYING_RANGE = "剪映专业版 6.0 – 6.9";

	/** 支持区间只读透出（任务书 #69 卡A）：导出端点响应头与前端提示同源于此。 */
	public static String supportedRange() {
		return SUPPORTED_JIANYING_RANGE;
	}

	/** 单镜输入：段 mp4、旁白 wav、时长与文本。 */
	public record ShotInput(int seq, long durationMs, byte[] segmentMp4, byte[] audioWav, String narration) {
	}

	/** 构建产物：两份 JSON + 媒体副本（相对 draft 根路径 → 字节）。 */
	public record DraftBuild(String draftContentJson, String draftMetaJson, Map<String, byte[]> materials) {
	}

	private final ObjectMapper mapper = new ObjectMapper();

	public DraftBuild build(String draftId, String draftName, boolean landscape, List<ShotInput> shots) {
		List<Map<String, Object>> videoMaterials = new ArrayList<>();
		List<Map<String, Object>> audioMaterials = new ArrayList<>();
		List<Map<String, Object>> textMaterials = new ArrayList<>();
		List<Map<String, Object>> videoSegments = new ArrayList<>();
		List<Map<String, Object>> audioSegments = new ArrayList<>();
		List<Map<String, Object>> textSegments = new ArrayList<>();
		Map<String, byte[]> materials = new LinkedHashMap<>();

		long startUs = 0;
		for (ShotInput shot : shots) {
			long durationUs = Math.max(1L, shot.durationMs()) * 1000L;
			String videoMaterialId = stableId(draftId, "video", shot.seq());
			String videoPath = materialPath(draftName, "video", shot.seq(), "mp4");
			videoMaterials.add(material(videoMaterialId, videoPath, durationUs, false));
			videoSegments.add(
					segment(stableId(draftId, "vseg", shot.seq()), videoMaterialId, startUs, durationUs, List.of()));
			if (shot.segmentMp4() != null) {
				materials.put(relMaterialPath("video", shot.seq(), "mp4"), shot.segmentMp4());
			}

			if (shot.audioWav() != null && shot.audioWav().length > 0) {
				String audioMaterialId = stableId(draftId, "audio", shot.seq());
				String audioPath = materialPath(draftName, "audio", shot.seq(), "wav");
				audioMaterials.add(material(audioMaterialId, audioPath, durationUs, true));
				audioSegments.add(segment(stableId(draftId, "aseg", shot.seq()), audioMaterialId, startUs, durationUs,
						List.of()));
				materials.put(relMaterialPath("audio", shot.seq(), "wav"), shot.audioWav());
			}

			if (shot.narration() != null && !shot.narration().isBlank()) {
				String textMaterialId = stableId(draftId, "text", shot.seq());
				Map<String, Object> textMaterial = new LinkedHashMap<>();
				textMaterial.put("id", textMaterialId);
				textMaterial.put("content", textContent(shot));
				textMaterial.put("type", "text");
				textMaterials.add(textMaterial);
				textSegments.add(
						segment(stableId(draftId, "tseg", shot.seq()), textMaterialId, startUs, durationUs, List.of()));
			}
			startUs += durationUs;
		}

		Map<String, Object> content = new LinkedHashMap<>();
		content.put("canvas_config", canvasConfig(landscape));
		content.put("duration", startUs);
		content.put("fps", 30);
		content.put("group_container_id", "");
		content.put("id", draftId);
		content.put("keyframe_graph_list", List.of());
		content.put("keyframe_graph_list_new", List.of());
		content.put("last_modified_platform", List.of("mac", "win"));
		content.put("materials", materialsOf(videoMaterials, audioMaterials, textMaterials));
		content.put("name", draftName);
		Map<String, Object> platform = new LinkedHashMap<>();
		platform.put("os", "mac");
		content.put("platform", platform);
		content.put("relationships", List.of());
		content.put("retouch_cover", "retouch_cover.jpg");
		content.put("source", "default");
		content.put("static_cover_image_path", "");
		content.put("tracks", tracksOf(videoSegments, audioSegments, textSegments));
		content.put("update_time", 0);
		content.put("version", 360000);

		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("draft_cloud_last_action_download", false);
		meta.put("draft_cloud_purchase_info", "");
		meta.put("draft_cloud_template_id", "");
		meta.put("draft_cover", "draft_cover.jpg");
		meta.put("draft_deeplink_url", "");
		meta.put("draft_fold_path", "");
		meta.put("draft_id", draftId);
		meta.put("draft_is_ai_shorts", false);
		meta.put("draft_is_article_video_draft", false);
		meta.put("draft_is_from_deeplink", false);
		meta.put("draft_materials", new ArrayList<>());
		meta.put("draft_micro_se", 0);
		meta.put("draft_name", draftName);
		meta.put("draft_new_version", "");
		meta.put("draft_removable_storage_device", "");
		meta.put("draft_root_path", "");
		meta.put("draft_timeline_materials_size", 0);
		meta.put("draft_type", "");
		meta.put("tm_draft_create", "");
		meta.put("tm_draft_modified", "");
		meta.put("tm_duration", startUs);

		return new DraftBuild(toJson(content), toJson(meta), materials);
	}

	// ---------------- 结构装配 ----------------

	private static Map<String, Object> canvasConfig(boolean landscape) {
		Map<String, Object> canvas = new LinkedHashMap<>();
		canvas.put("width", landscape ? 1920 : 1080);
		canvas.put("height", landscape ? 1080 : 1920);
		canvas.put("ratio", landscape ? "16:9" : "9:16");
		return canvas;
	}

	private static Map<String, Object> materialsOf(List<Map<String, Object>> videos, List<Map<String, Object>> audios,
			List<Map<String, Object>> texts) {
		Map<String, Object> materials = new LinkedHashMap<>();
		materials.put("material_animations", List.of());
		materials.put("material_animations_new", List.of());
		materials.put("material_audio_balances", List.of());
		materials.put("material_audio_effects", List.of());
		materials.put("material_audio_fades", List.of());
		materials.put("material_audios", audios);
		materials.put("material_beats", List.of());
		materials.put("material_caiyans", List.of());
		materials.put("material_chromas", List.of());
		materials.put("material_color_curves", List.of());
		materials.put("material_colors", List.of());
		materials.put("material_crops", List.of());
		materials.put("material_digital_humans", List.of());
		materials.put("material_drafts", List.of());
		materials.put("material_effects", List.of());
		materials.put("material_filters", List.of());
		materials.put("material_fonts", List.of());
		materials.put("material_handcuts", List.of());
		materials.put("material_holograms", List.of());
		materials.put("material_inks", List.of());
		materials.put("material_masks", List.of());
		materials.put("material_manners", List.of());
		materials.put("material_materials", List.of());
		materials.put("material_motions", List.of());
		materials.put("material_placeholders", List.of());
		materials.put("material_realtime_denoises", List.of());
		materials.put("material_renditions", List.of());
		materials.put("material_reverse", List.of());
		materials.put("material_roaudios", List.of());
		materials.put("material_speeds", List.of());
		materials.put("material_stickers", List.of());
		materials.put("material_texts", texts);
		materials.put("material_time_marks", List.of());
		materials.put("material_tracks", List.of());
		materials.put("material_transitions", List.of());
		materials.put("material_video_effects", List.of());
		materials.put("material_videos", videos);
		materials.put("material_vocal_beautifys", List.of());
		materials.put("material_vocal_fades", List.of());
		return materials;
	}

	private static List<Map<String, Object>> tracksOf(List<Map<String, Object>> videoSegments,
			List<Map<String, Object>> audioSegments, List<Map<String, Object>> textSegments) {
		List<Map<String, Object>> tracks = new ArrayList<>();
		if (!videoSegments.isEmpty()) {
			tracks.add(track("video", videoSegments));
		}
		if (!audioSegments.isEmpty()) {
			tracks.add(track("audio", audioSegments));
		}
		if (!textSegments.isEmpty()) {
			tracks.add(track("text", textSegments));
		}
		return tracks;
	}

	private static Map<String, Object> track(String type, List<Map<String, Object>> segments) {
		Map<String, Object> track = new LinkedHashMap<>();
		track.put("attribute", 0);
		track.put("flag", 0);
		track.put("id", stableId("track", type, segments.size()));
		track.put("segments", segments);
		track.put("type", type);
		return track;
	}

	private static Map<String, Object> material(String id, String path, long durationUs, boolean audio) {
		Map<String, Object> material = new LinkedHashMap<>();
		material.put("id", id);
		material.put("path", path);
		material.put("duration", durationUs);
		if (audio) {
			material.put("type", "extract_music");
			material.put("music_id", "");
			material.put("name", path.substring(path.lastIndexOf('/') + 1));
			material.put("source_platform", 0);
		} else {
			material.put("type", "video");
			material.put("has_audio", false);
			material.put("source_platform", 0);
		}
		return material;
	}

	private static Map<String, Object> segment(String id, String materialId, long startUs, long durationUs,
			List<String> extraRefs) {
		Map<String, Object> timerange = new LinkedHashMap<>();
		timerange.put("start", startUs);
		timerange.put("duration", durationUs);
		Map<String, Object> segment = new LinkedHashMap<>();
		segment.put("cartoon", false);
		segment.put("clip", clipOf(durationUs));
		segment.put("enable_adjust", true);
		segment.put("extra_material_refs", extraRefs);
		segment.put("group_id", "");
		segment.put("id", id);
		segment.put("intensifies_audio", false);
		segment.put("material_id", materialId);
		segment.put("render_index", 0);
		segment.put("source", null);
		segment.put("target_timerange", timerange);
		return segment;
	}

	private static Map<String, Object> clipOf(long durationUs) {
		Map<String, Object> timerange = new LinkedHashMap<>();
		timerange.put("start", 0);
		timerange.put("duration", durationUs);
		Map<String, Object> clip = new LinkedHashMap<>();
		clip.put("alpha", 1.0);
		clip.put("flip", flipOf());
		clip.put("rotation", 0);
		clip.put("scale", scaleOf());
		clip.put("transform", transformOf());
		return clip;
	}

	private static Map<String, Object> flipOf() {
		Map<String, Object> flip = new LinkedHashMap<>();
		flip.put("horizontal", false);
		flip.put("vertical", false);
		return flip;
	}

	private static Map<String, Object> scaleOf() {
		Map<String, Object> scale = new LinkedHashMap<>();
		scale.put("x", 1.0);
		scale.put("y", 1.0);
		return scale;
	}

	private static Map<String, Object> transformOf() {
		Map<String, Object> transform = new LinkedHashMap<>();
		transform.put("alpha", 1.0);
		transform.put("position", List.of(0.0, 0.0));
		return transform;
	}

	/** 字幕文本 content：剪映把富文本嵌在字符串化 JSON 里（最小集只带正文与字号）。 */
	private String textContent(ShotInput shot) {
		Map<String, Object> content = new LinkedHashMap<>();
		content.put("text", shot.narration());
		Map<String, Object> font = new LinkedHashMap<>();
		font.put("size", 8);
		content.put("font", font);
		return toJson(content);
	}

	// ---------------- 工具 ----------------

	static String draftNameOf(UUID taskId) {
		return "caochang-video-" + taskId.toString().substring(0, 8);
	}

	private static String materialPath(String draftName, String folder, int seq, String ext) {
		// 绝对路径占位：用户把解包目录放进剪映 draft 根后由剪映重绑（非公开契约的容错行为），
		// 相对路径在部分版本会被识别为损坏草稿。
		return "/JianYingDrafts/" + draftName + "/" + relMaterialPath(folder, seq, ext);
	}

	private static String relMaterialPath(String folder, int seq, String ext) {
		return "materials/" + folder + "/shot-" + seq + "." + ext;
	}

	private static String stableId(String namespace, String kind, Object seq) {
		return UUID.nameUUIDFromBytes((namespace + "|" + kind + "|" + seq).getBytes(StandardCharsets.UTF_8)).toString();
	}

	private String toJson(Map<String, Object> value) {
		try {
			return mapper.writeValueAsString(value);
		} catch (IOException error) {
			throw new IllegalStateException("剪映草稿 JSON 序列化失败", error);
		}
	}

	/** 分镜稿文案复用（B1 同源）：镜头序号与旁白对齐。 */
	static String narrationOf(VideoShot shot) {
		return shot.narration();
	}
}
