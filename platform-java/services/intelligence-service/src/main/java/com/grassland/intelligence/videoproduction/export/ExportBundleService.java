package com.grassland.intelligence.videoproduction.export;

import com.grassland.intelligence.creationlineage.CreationGeneration;
import com.grassland.intelligence.creationlineage.CreationGenerationRecorder;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.speech.AudioDurationProbe;
import com.grassland.intelligence.videoproduction.VideoProductionTask;
import com.grassland.intelligence.videoproduction.VideoProductionTaskRepository;
import com.grassland.intelligence.videoproduction.VideoShot;
import com.grassland.intelligence.videoproduction.VideoShotAudio;
import com.grassland.intelligence.videoproduction.VideoShotAudioRepository;
import com.grassland.intelligence.videoproduction.VideoShotRepository;
import com.grassland.intelligence.videoproduction.VideoStoryboardRepository;
import com.grassland.storage.ObjectStorageAdapter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * B 轨通用素材包导出（任务书 #66 卡B1，§3 布局契约）：
 *
 * <pre>
 * bundle/分镜稿.md              逐镜：序号/画面/旁白/时长/运镜
 * bundle/audio/shot-{n}.wav     逐镜旁白（TTS 归档件）
 * bundle/subtitle.srt           成片字幕（合成时归档）
 * bundle/segments/shot-{n}.mp4  逐镜段（#65 段缓存直接复用）
 * bundle/master.mp4             成片
 * </pre>
 *
 * <p>
 * 权限=任务属主（findById 带账号，非属主 404 不泄露存在性——与详情/字幕端点同口径）； 仅 phase=succeeded
 * 可导出（409）；zip 汇集后写对象存储、一次性 presign；每次导出落
 * creationlineage（kind=video_export）审计。段缓存已被卡10 清理回收的镜头条目静默缺省
 * （master/SRT/分镜稿恒在），导出 advisory 不阻断。
 */
@Service
public class ExportBundleService {

	private static final Logger log = LoggerFactory.getLogger(ExportBundleService.class);
	private static final String BUNDLE_KEY_PREFIX = "media/video_export/";
	private static final String SEGMENT_KEY_PREFIX = "segments/";

	public record ExportArtifact(String downloadUrl, long expiresInSeconds, int entryCount, UUID taskId,
			int recomposeSeq, UUID finalMediaId) {
	}

	/** A 轨产物：zip 里是剪映 draft 目录（draft_name 供前端展示）。 */
	public record JianyingArtifact(String downloadUrl, long expiresInSeconds, int entryCount, String draftName,
			UUID taskId, int recomposeSeq, UUID finalMediaId) {
	}

	private final VideoProductionTaskRepository tasks;
	private final VideoShotRepository shots;
	private final VideoShotAudioRepository audios;
	private final MediaReferenceRepository mediaRefs;
	private final CreationGenerationRecorder lineage;
	private final ObjectProvider<ObjectStorageAdapter> storageProvider;
	private final VideoStoryboardRepository storyboards;
	private final JianyingDraftBuilder jianyingBuilder;
	private final AudioDurationProbe durationProbe;
	private final com.grassland.intelligence.videoproduction.VideoShotSourceRepository sourceRows;

	private final org.springframework.r2dbc.core.DatabaseClient db;
	private final org.springframework.transaction.reactive.TransactionalOperator transactions;
	private final com.grassland.intelligence.creationcanvas.CanvasProjectAccess projects;
	private final com.grassland.intelligence.creationcanvas.VideoCanvasWorkspaceRepository bindings;

	private record ExportSnapshot(VideoProductionTask task, List<VideoShot> shots, List<VideoShotAudio> audios,
			Map<UUID, com.grassland.intelligence.videoproduction.VideoShotSource> sources,
			com.grassland.intelligence.videoproduction.VideoStoryboard storyboard, MediaReference master,
			java.util.Optional<MediaReference> subtitle) {
	}

	public ExportBundleService(VideoProductionTaskRepository tasks, VideoShotRepository shots,
			VideoShotAudioRepository audios, MediaReferenceRepository mediaRefs, CreationGenerationRecorder lineage,
			ObjectProvider<ObjectStorageAdapter> storageProvider, VideoStoryboardRepository storyboards,
			JianyingDraftBuilder jianyingBuilder, AudioDurationProbe durationProbe,
			com.grassland.intelligence.videoproduction.VideoShotSourceRepository sourceRows,
			org.springframework.r2dbc.core.DatabaseClient db,
			org.springframework.transaction.reactive.TransactionalOperator transactions,
			com.grassland.intelligence.creationcanvas.CanvasProjectAccess projects,
			com.grassland.intelligence.creationcanvas.VideoCanvasWorkspaceRepository bindings) {
		this.tasks = tasks;
		this.shots = shots;
		this.audios = audios;
		this.mediaRefs = mediaRefs;
		this.lineage = lineage;
		this.storageProvider = storageProvider;
		this.storyboards = storyboards;
		this.jianyingBuilder = jianyingBuilder;
		this.durationProbe = durationProbe;
		this.sourceRows = sourceRows;
		this.db = db;
		this.transactions = transactions;
		this.projects = projects;
		this.bindings = bindings;
	}

	public Mono<ExportArtifact> exportBundle(UUID taskId, String accountId, long ttlSeconds) {
		return exportBundle(taskId, accountId, ttlSeconds, null);
	}
	public Mono<ExportArtifact> exportBundle(UUID taskId, String accountId, long ttlSeconds,
			Integer expectedRecomposeSeq) {
		return snapshot(taskId, accountId, expectedRecomposeSeq).flatMap(snapshot -> assemble(snapshot, ttlSeconds));
	}
	public Mono<JianyingArtifact> exportJianying(UUID taskId, String accountId, long ttlSeconds) {
		return exportJianying(taskId, accountId, ttlSeconds, null);
	}
	public Mono<JianyingArtifact> exportJianying(UUID taskId, String accountId, long ttlSeconds,
			Integer expectedRecomposeSeq) {
		return snapshot(taskId, accountId, expectedRecomposeSeq)
				.flatMap(snapshot -> assembleJianying(snapshot, ttlSeconds));
	}

	/**
	 * Short transaction captures DB metadata only; no storage, ZIP or FFmpeg work
	 * holds these locks.
	 */
	private Mono<ExportSnapshot> snapshot(UUID taskId, String accountId, Integer expected) {
		if (expected != null && expected < 0)
			return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "成片版本必须为非负整数"));
		return tasks.findById(taskId, accountId).switchIfEmpty(Mono.error(new IntelligenceException(404, "任务不存在")))
				.flatMap(initial -> bindings.findByStoryboard(initial.storyboardId())
						.flatMap(binding -> projects.lockProjects(accountId,
								List.of(new com.grassland.intelligence.creationcanvas.CanvasProjectAccess.ProjectKey(
										binding.draftId(), initial.storyboardId())))
								.thenReturn(true))
						.switchIfEmpty(storyboards.lockById(initial.storyboardId(), accountId).map(ignored -> true))
						.then(db.sql(
								"SELECT id FROM video_production_task WHERE id=:id AND account_id=:account FOR UPDATE")
								.bind("id", taskId).bind("account", accountId).map(row -> row.get(0, UUID.class)).one())
						.then(tasks.findById(taskId, accountId)))
				.flatMap(task -> {
					if (!VideoProductionTask.PHASE_SUCCEEDED.equals(task.phase()))
						return Mono.error(new IntelligenceException(409, "CANVAS_RESOURCE_LOCKED", "任务尚未完成，暂不能导出"));
					if (expected != null && expected != task.recomposeSeq())
						return Mono.error(versionConflict());
					return Mono
							.zip(shots.findByStoryboard(task.storyboardId()).collectList(),
									audios.findByStoryboard(task.storyboardId()).collectList(),
									sourceRows.findByStoryboard(task.storyboardId()).collectMap(
											com.grassland.intelligence.videoproduction.VideoShotSource::shotId),
									storyboards.findById(task.storyboardId()),
									mediaMetadata(task.finalMediaId(), accountId)
											.switchIfEmpty(Mono.error(unavailableMaster())),
									mediaMetadata(task.srtMediaId(), accountId).map(java.util.Optional::of)
											.defaultIfEmpty(java.util.Optional.empty()))
							.map(data -> new ExportSnapshot(task, data.getT1(), data.getT2(), data.getT3(),
									data.getT4(), data.getT5(), data.getT6()));
				}).as(transactions::transactional);
	}

	private Mono<MediaReference> mediaMetadata(UUID id, String accountId) {
		if (id == null)
			return Mono.empty();
		return mediaRefs.findById(id)
				.filter(ref -> accountId.equals(ref.ownerAccountId()) && ref.deletedAt() == null
						&& ref.status() == com.grassland.intelligence.media.MediaStatus.ACTIVE
						&& (ref.expiresAt() == null || ref.expiresAt().isAfter(java.time.Instant.now())));
	}
	private Mono<Void> verifySnapshot(ExportSnapshot snapshot) {
		var original = snapshot.task();
		return Mono
				.zip(tasks.findById(original.id(), original.accountId()), storyboards.findById(original.storyboardId()))
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "任务不存在"))).flatMap(tuple -> {
					var current = tuple.getT1();
					boolean same = current.recomposeSeq() == original.recomposeSeq()
							&& current.selectionVersion() == original.selectionVersion()
							&& java.util.Objects.equals(current.finalMediaId(), original.finalMediaId())
							&& java.util.Objects.equals(current.srtMediaId(), original.srtMediaId())
							&& VideoProductionTask.PHASE_SUCCEEDED.equals(current.phase())
							&& tuple.getT2().editVersion() == snapshot.storyboard().editVersion();
					return same ? Mono.empty() : Mono.error(versionConflict());
				});
	}
	private static IntelligenceException versionConflict() {
		return new IntelligenceException(409, "CANVAS_VERSION_CONFLICT", "成片版本已变化，请重新确认后导出");
	}
	private static IntelligenceException unavailableMaster() {
		return new IntelligenceException(409, "CANVAS_MEDIA_UNAVAILABLE", "成片主文件不可用，无法导出");
	}
	private byte[] masterBytes(ExportSnapshot snapshot) {
		byte[] bytes = objectBytes(requireStorage(), snapshot.master().objectKey());
		if (bytes == null)
			throw unavailableMaster();
		return bytes;
	}

	private Mono<JianyingArtifact> assembleJianying(ExportSnapshot snapshot, long ttlSeconds) {
		var task = snapshot.task();
		return Mono.fromCallable(() -> {
			masterBytes(snapshot);
			return buildJianyingZip(task, snapshot.shots(), snapshot.audios(), snapshot.storyboard());
		}).subscribeOn(Schedulers.boundedElastic()).flatMap(draft -> {
			String key = jianyingKey(task.id(), task.recomposeSeq());
			ObjectStorageAdapter storage = requireStorage();
			return verifySnapshot(snapshot)
					.then(Mono.fromRunnable(() -> storage.putObject(key, draft.zipBytes(), "application/zip"))
							.subscribeOn(Schedulers.boundedElastic()))
					.then(verifySnapshot(snapshot)).then(recordLineage(task, "jianying", draft.entryCount()))
					.thenReturn(new JianyingArtifact(
							storage.presignDownload(key, ttlSeconds,
									"attachment; filename=\"video-jianying-" + task.id() + ".zip\"").toString(),
							ttlSeconds, draft.entryCount(), draft.draftName(), task.id(), task.recomposeSeq(),
							task.finalMediaId()));
		});
	}

	private record JianyingAssembled(byte[] zipBytes, int entryCount, String draftName) {
	}

	private JianyingAssembled buildJianyingZip(VideoProductionTask task, List<VideoShot> shotList,
			List<VideoShotAudio> audioList, com.grassland.intelligence.videoproduction.VideoStoryboard storyboard)
			throws IOException {
		String draftName = JianyingDraftBuilder.draftNameOf(task.id());
		boolean landscape = "landscape".equals(storyboard.resolutionOrDefault());
		Map<String, VideoShotAudio> audioByShotId = new LinkedHashMap<>();
		audioList.forEach(audio -> audioByShotId.put(audio.shotId().toString(), audio));
		ObjectStorageAdapter storage = storageProvider.getIfAvailable();

		List<JianyingDraftBuilder.ShotInput> inputs = new ArrayList<>();
		for (VideoShot shot : shotList) {
			byte[] segment = storage == null
					? null
					: objectBytes(storage, SEGMENT_KEY_PREFIX + task.id() + "/" + shot.id() + ".mp4");
			VideoShotAudio audio = audioByShotId.get(shot.id().toString());
			byte[] audioBytes = audio == null || audio.mediaId() == null || storage == null
					? null
					: objectBytes(storage, "media/video_shot_audio/" + audio.mediaId());
			long durationMs = plannedDurationMs(shot, segment);
			inputs.add(
					new JianyingDraftBuilder.ShotInput(shot.seq(), durationMs, segment, audioBytes, shot.narration()));
		}
		JianyingDraftBuilder.DraftBuild draft = jianyingBuilder.build(task.id().toString(), draftName, landscape,
				inputs);

		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		int entries = 0;
		try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
			zip.putNextEntry(new ZipEntry("jianying/" + draftName + "/draft_content.json"));
			zip.write(draft.draftContentJson().getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("jianying/" + draftName + "/draft_meta_info.json"));
			var metadata = (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper()
					.readTree(draft.draftMetaJson());
			metadata.putObject("grassland").put("taskId", task.id().toString()).put("recomposeSeq", task.recomposeSeq())
					.put("finalMediaId", task.finalMediaId().toString());
			zip.write(metadata.toString().getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			entries += 2;
			for (Map.Entry<String, byte[]> material : draft.materials().entrySet()) {
				zip.putNextEntry(new ZipEntry("jianying/" + draftName + "/" + material.getKey()));
				zip.write(material.getValue());
				zip.closeEntry();
				entries++;
			}
			zip.finish();
		}
		return new JianyingAssembled(buffer.toByteArray(), entries, draftName);
	}

	/** 段时长：ffprobe 实测优先（合成段与 planned 有出入），探不到回退计划时长。 */
	private long plannedDurationMs(VideoShot shot, byte[] segment) {
		if (segment != null) {
			try {
				return durationProbe.probe(segment);
			} catch (RuntimeException probeFailed) {
				// 回退计划时长
			}
		}
		return shot.plannedSeconds() * 1000L;
	}

	private Mono<ExportArtifact> assemble(ExportSnapshot snapshot, long ttlSeconds) {
		var task = snapshot.task();
		return Mono
				.fromCallable(() -> zip(task, snapshot.shots(), snapshot.audios(), snapshot.sources(),
						masterBytes(snapshot),
						snapshot.subtitle().map(ref -> objectBytes(requireStorage(), ref.objectKey())).orElse(null)))
				.subscribeOn(Schedulers.boundedElastic()).flatMap(bundle -> {
					String key = bundleKey(task.id(), task.recomposeSeq());
					ObjectStorageAdapter storage = requireStorage();
					return verifySnapshot(snapshot)
							.then(Mono.fromRunnable(() -> storage.putObject(key, bundle.zipBytes(), "application/zip"))
									.subscribeOn(Schedulers.boundedElastic()))
							.then(verifySnapshot(snapshot)).then(
									recordLineage(task, "bundle", bundle.entryCount()))
							.thenReturn(new ExportArtifact(
									storage.presignDownload(key, ttlSeconds,
											"attachment; filename=\"video-bundle-" + task.id() + ".zip\"").toString(),
									ttlSeconds, bundle.entryCount(), task.id(), task.recomposeSeq(),
									task.finalMediaId()));
				});
	}

	private record AssembledBundle(byte[] zipBytes, int entryCount) {
	}

	/** 导出 manifest：每镜实际采用源与截取（TC-031 导出源一致性的机器可读面）。 */
	private String sourcesManifest(VideoProductionTask task, List<VideoShot> shotList,
			java.util.Map<java.util.UUID, com.grassland.intelligence.videoproduction.VideoShotSource> sources) {
		StringBuilder json = new StringBuilder("{\"taskId\":\"" + task.id() + "\",\"recomposeSeq\":"
				+ task.recomposeSeq() + ",\"finalMediaId\":\"" + task.finalMediaId() + "\",\"shots\":[");
		boolean first = true;
		for (VideoShot shot : shotList) {
			com.grassland.intelligence.videoproduction.VideoShotSource source = sources.get(shot.id());
			if (!first) {
				json.append(',');
			}
			first = false;
			json.append("{\"seq\":").append(shot.seq()).append(",\"shotId\":\"").append(shot.id()).append('"')
					.append(",\"source\":");
			if (source == null) {
				json.append("{\"kind\":\"generated\"}");
			} else {
				json.append("{\"kind\":\"").append(source.sourceKind()).append('"');
				if (source.mediaId() != null) {
					json.append(",\"mediaId\":\"").append(source.mediaId()).append('"');
				}
				if (source.trimStartMs() != null) {
					json.append(",\"trimStartMs\":").append(source.trimStartMs());
				}
				if (source.trimEndMs() != null) {
					json.append(",\"trimEndMs\":").append(source.trimEndMs());
				}
				json.append(",\"audioMode\":\"").append(source.audioMode()).append('"');
				json.append('}');
			}
			json.append('}');
		}
		json.append("]}");
		return json.toString();
	}

	private AssembledBundle zip(VideoProductionTask task, List<VideoShot> shotList,
			List<com.grassland.intelligence.videoproduction.VideoShotAudio> audioList,
			java.util.Map<java.util.UUID, com.grassland.intelligence.videoproduction.VideoShotSource> sources,
			byte[] masterBytes, byte[] srtBytes) throws IOException {
		Map<String, com.grassland.intelligence.videoproduction.VideoShotAudio> audioByShotId = new LinkedHashMap<>();
		audioList.forEach(audio -> audioByShotId.put(audio.shotId().toString(), audio));

		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		int entries = 0;
		try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
			zip.putNextEntry(new ZipEntry("bundle/分镜稿.md"));
			zip.write(storyboardMarkdown(task, shotList).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			entries++;
			// 任务书 #100 C100-13：manifest 声明每镜实际采用源（own 截取/音轨策略）
			zip.putNextEntry(new ZipEntry("bundle/manifest.json"));
			zip.write(sourcesManifest(task, shotList, sources).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			entries++;

			if (masterBytes != null) {
				zip.putNextEntry(new ZipEntry("bundle/master.mp4"));
				zip.write(masterBytes);
				zip.closeEntry();
				entries++;
			}
			if (srtBytes != null) {
				zip.putNextEntry(new ZipEntry("bundle/subtitle.srt"));
				zip.write(srtBytes);
				zip.closeEntry();
				entries++;
			}
			ObjectStorageAdapter storage = storageProvider.getIfAvailable();
			for (VideoShot shot : shotList) {
				com.grassland.intelligence.videoproduction.VideoShotAudio audio = audioByShotId
						.get(shot.id().toString());
				if (audio != null && audio.mediaId() != null) {
					byte[] audioBytes = objectBytes(storage, "media/video_shot_audio/" + audio.mediaId());
					if (audioBytes != null) {
						zip.putNextEntry(new ZipEntry("bundle/audio/shot-" + shot.seq() + ".wav"));
						zip.write(audioBytes);
						zip.closeEntry();
						entries++;
					}
				}
				// 段缓存被清理回收的镜头条目静默缺省（advisory，master 恒在）
				byte[] segment = objectBytes(storage, SEGMENT_KEY_PREFIX + task.id() + "/" + shot.id() + ".mp4");
				if (segment != null) {
					zip.putNextEntry(new ZipEntry("bundle/segments/shot-" + shot.seq() + ".mp4"));
					zip.write(segment);
					zip.closeEntry();
					entries++;
				}
			}
			zip.finish();
		}
		return new AssembledBundle(buffer.toByteArray(), entries);
	}

	/** 逐镜分镜稿（§3 模板：`## 镜头N / 时长 / 运镜` + 画面 + 旁白）。 */
	static String storyboardMarkdown(VideoProductionTask task, List<VideoShot> shotList) {
		StringBuilder md = new StringBuilder();
		md.append("# 分镜稿\n\n");
		md.append("- 任务：").append(task.id()).append('\n');
		md.append("- 模式：").append(task.mode()).append('\n');
		md.append("- 目标时长：").append(task.targetDurationSeconds()).append(" 秒\n");
		if (task.actualDurationSeconds() != null) {
			md.append("- 实际时长：").append(task.actualDurationSeconds()).append(" 秒\n");
		}
		md.append('\n');
		for (VideoShot shot : shotList) {
			md.append("## 镜头").append(shot.seq()).append(" / ").append(shot.plannedSeconds()).append(" 秒 / ")
					.append(shot.cameraMove() == null ? "—" : shot.cameraMove()).append("\n\n");
			md.append("- 画面：").append(nullToDash(shot.visual())).append('\n');
			md.append("- 旁白：").append(nullToDash(shot.narration())).append('\n');
			md.append('\n');
		}
		return md.toString();
	}

	private Mono<Void> recordLineage(VideoProductionTask task, String format, int entryCount) {
		List<UUID> resultMediaIds = new ArrayList<>();
		if (task.finalMediaId() != null) {
			resultMediaIds.add(task.finalMediaId());
		}
		if (task.srtMediaId() != null) {
			resultMediaIds.add(task.srtMediaId());
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("format", format);
		result.put("entries", entryCount);
		result.put("masterMediaId", task.finalMediaId() == null ? null : task.finalMediaId().toString());
		return lineage.record(new CreationGenerationRecorder.Command(CreationGeneration.Kind.VIDEO_EXPORT,
				CreationGeneration.Mode.TASK, task.contextSnapshotId(), task.runId(),
				CreationGeneration.Resolution.PLATFORM, task.provider(), task.model(), task.platformModelVersion(),
				null, "video export bundle", Map.of(), List.of(), result, resultMediaIds, task.accountId(),
				task.organizationId())).onErrorResume(error -> {
					// lineage 登记失败不阻断导出（审计 advisory），仅记日志
					log.warn("video export lineage record failed taskId={} cause={}", task.id(),
							String.valueOf(error.getMessage()));
					return Mono.empty();
				}).then();
	}

	public static String bundleKey(UUID taskId) {
		return bundleKey(taskId, 0);
	}

	public static String jianyingKey(UUID taskId) {
		return jianyingKey(taskId, 0);
	}

	public static String bundleKey(UUID taskId, int recomposeSeq) {
		return BUNDLE_KEY_PREFIX + taskId + "/" + recomposeSeq + "/bundle.zip";
	}
	public static String jianyingKey(UUID taskId, int recomposeSeq) {
		return BUNDLE_KEY_PREFIX + taskId + "/" + recomposeSeq + "/jianying.zip";
	}

	private static byte[] objectBytes(ObjectStorageAdapter storage, String key) {
		try {
			byte[] bytes = storage.getObject(key);
			return bytes == null || bytes.length == 0 ? null : bytes;
		} catch (RuntimeException missingOrFailed) {
			return null;
		}
	}

	private ObjectStorageAdapter requireStorage() {
		ObjectStorageAdapter storage = storageProvider.getIfAvailable();
		if (storage == null) {
			throw new IntelligenceException(503, "对象存储未启用");
		}
		return storage;
	}

	private static String nullToDash(String value) {
		return value == null || value.isBlank() ? "—" : value;
	}
}
