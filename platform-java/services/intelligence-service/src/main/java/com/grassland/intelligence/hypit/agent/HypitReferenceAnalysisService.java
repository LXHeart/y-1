package com.grassland.intelligence.hypit.agent;

import com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Evidence;
import com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Gap;
import com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Segment;
import com.grassland.intelligence.hypit.job.HypitCommandRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 全片参考理解服务（任务书 #107-2 C107-15 / K11.3）：把分段观察合并成 ReferenceAnalysis（coverage
 * 并集、跨切镜系统合并、观察/推断分列），持久进 C04 幂等命令（action=reference.analyze，requestId=稳定
 * operationId），并生成 ANALYSIS.md/TIMELINE.md。增量重分析只替换指定段，其余证据原样保留（步骤 8）。
 *
 * <p>
 * LLM 回放（确定性 fixture 路径）与真实模型调用分开标记：真实 LLM/真实转写 REAL_NOT_RUN，未配置时分析停在
 * WAITING_INPUT，绝不以缩略图伪造全片结论。
 */
@Service
public class HypitReferenceAnalysisService {

	private final HypitCommandRepository commands;

	public HypitReferenceAnalysisService(HypitCommandRepository commands) {
		this.commands = commands;
	}

	/** API 层的宽松重建：泛型 map → 强类型 analysis（未登记字段忽略）。 */
	public static HypitReferenceAnalysis fromMap(Map<String, Object> map) {
		return new HypitReferenceAnalysis(String.valueOf(map.get("analysisId")), String.valueOf(map.get("mediaHash")),
				map.get("durationSeconds") instanceof Number number ? number.doubleValue() : 0.0,
				map.get("language") == null ? null : String.valueOf(map.get("language")),
				map.get("aspectRatio") == null ? null : String.valueOf(map.get("aspectRatio")),
				typedList(map.get("segments"), value -> {
					Map<String, Object> item = (Map<String, Object>) value;
					@SuppressWarnings("unchecked")
					Map<String, Object> casted = item;
					return new HypitReferenceAnalysis.Segment(((Number) casted.getOrDefault("index", 0)).intValue(),
							((Number) casted.getOrDefault("startSeconds", 0)).doubleValue(),
							((Number) casted.getOrDefault("endSeconds", 0)).doubleValue(),
							String.valueOf(casted.get("summary")), typedList(casted.get("evidence"), ev -> {
								Map<String, Object> evidence = (Map<String, Object>) ev;
								return new HypitReferenceAnalysis.Evidence(String.valueOf(evidence.get("assetId")),
										((Number) evidence.getOrDefault("sourceTimeSeconds", 0)).doubleValue(),
										String.valueOf(evidence.get("note")));
							}));
				}), typedList(map.get("systems"), value -> {
					Map<String, Object> item = (Map<String, Object>) value;
					return new HypitReferenceAnalysis.System(String.valueOf(item.get("systemId")),
							String.valueOf(item.get("kind")), String.valueOf(item.get("name")),
							((Number) item.getOrDefault("firstSeenSeconds", 0)).doubleValue(),
							((Number) item.getOrDefault("lastSeenSeconds", 0)).doubleValue(),
							typedList(item.get("segmentIndexes"), index -> String.valueOf(index)));
				}), typedList(map.get("events"), value -> {
					Map<String, Object> item = (Map<String, Object>) value;
					return new HypitReferenceAnalysis.Event(String.valueOf(item.get("kind")),
							((Number) item.getOrDefault("atSeconds", 0)).doubleValue(),
							item.get("trigger") == null ? null : String.valueOf(item.get("trigger")),
							String.valueOf(item.get("evidenceAsset")), Boolean.TRUE.equals(item.get("inferred")));
				}), typedList(map.get("gaps"), value -> {
					Map<String, Object> item = (Map<String, Object>) value;
					return new HypitReferenceAnalysis.Gap(((Number) item.getOrDefault("startSeconds", 0)).doubleValue(),
							((Number) item.getOrDefault("endSeconds", 0)).doubleValue(),
							String.valueOf(item.get("reason")));
				}), typedList(map.get("openQuestions"), question -> String.valueOf(question)),
				HypitReferenceAnalysis.Status.valueOf(String.valueOf(map.get("status"))));
	}

	private static <T> List<T> typedList(Object raw, java.util.function.Function<Object, T> mapper) {
		List<T> list = new ArrayList<>();
		if (raw instanceof List<?> values) {
			for (Object item : values) {
				list.add(mapper.apply(item));
			}
		}
		return list;
	}

	/** 输入：探针事实 + 分段观察（证据必须带 asset/时间）。 */
	public record AnalysisInput(String operationId, String mediaHash, double durationSeconds, String language,
			String aspectRatio, boolean transcriptionReady, List<Segment> segments,
			List<HypitReferenceAnalysis.System> systems, List<HypitReferenceAnalysis.Event> events,
			List<String> openQuestions) {
	}

	public Mono<HypitReferenceAnalysis> analyze(AnalysisInput input) {
		List<Gap> gaps = HypitReferenceAnalysis.coverageGaps(input.durationSeconds(), input.segments(),
				transcriptionGapReason(input));
		// 状态机：转写未就绪 → WAITING_INPUT（视觉分析照常记录，音频证据待补）；
		// 有未覆盖区间 → PROVISIONAL（未查全片禁止 SUCCEEDED）；全覆盖 → SUCCEEDED。
		HypitReferenceAnalysis.Status status;
		if (!input.transcriptionReady()) {
			status = HypitReferenceAnalysis.Status.WAITING_INPUT;
		} else if (!gaps.isEmpty()) {
			status = HypitReferenceAnalysis.Status.PROVISIONAL;
		} else {
			status = HypitReferenceAnalysis.Status.SUCCEEDED;
		}
		// analysisId 由稳定 operationId 派生：同 operation 幂等回读同一分析。
		String analysisId = "ra-" + UUID.nameUUIDFromBytes(
				("reference-analysis:" + input.operationId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		HypitReferenceAnalysis analysis = new HypitReferenceAnalysis(analysisId, input.mediaHash(),
				input.durationSeconds(), input.language(), input.aspectRatio(), sortSegments(input.segments()),
				mergeSystems(input.systems()), sortEvents(input.events()), gaps, input.openQuestions(), status);
		return persist(input.operationId(), analysis).thenReturn(analysis);
	}

	private static String transcriptionGapReason(AnalysisInput input) {
		return input.transcriptionReady() ? "未被检查覆盖" : "转写未就绪，音频证据待补";
	}

	private static List<Segment> sortSegments(List<Segment> segments) {
		List<Segment> sorted = new ArrayList<>(segments);
		sorted.sort(Comparator.comparingDouble(Segment::startSeconds));
		return sorted;
	}

	private static List<HypitReferenceAnalysis.Event> sortEvents(List<HypitReferenceAnalysis.Event> events) {
		List<HypitReferenceAnalysis.Event> sorted = new ArrayList<>(events);
		sorted.sort(Comparator.comparingDouble(HypitReferenceAnalysis.Event::atSeconds));
		return sorted;
	}

	/**
	 * 步骤 5：同类系统跨切镜合并为稳定 systemId；事件变化保留在 events 里， 不因合并丢事件。合并键 = kind+name。
	 */
	public static List<HypitReferenceAnalysis.System> mergeSystems(List<HypitReferenceAnalysis.System> raw) {
		Map<String, HypitReferenceAnalysis.System> merged = new LinkedHashMap<>();
		for (HypitReferenceAnalysis.System system : raw) {
			String key = system.kind() + "|" + system.name();
			HypitReferenceAnalysis.System existing = merged.get(key);
			if (existing == null) {
				merged.put(key, system);
				continue;
			}
			List<String> segmentIndexes = new ArrayList<>(existing.segmentIndexes());
			for (String index : system.segmentIndexes()) {
				if (!segmentIndexes.contains(index)) {
					segmentIndexes.add(index);
				}
			}
			merged.put(key,
					new HypitReferenceAnalysis.System(existing.systemId(), existing.kind(), existing.name(),
							Math.min(existing.firstSeenSeconds(), system.firstSeenSeconds()),
							Math.max(existing.lastSeenSeconds(), system.lastSeenSeconds()), segmentIndexes));
		}
		return new ArrayList<>(merged.values());
	}

	/** 步骤 8：增量重分析——仅替换 reanalyze 段索引，其余段/事件/证据原样保留。 */
	public HypitReferenceAnalysis incremental(HypitReferenceAnalysis previous, AnalysisInput patch,
			List<Integer> segmentIndexes) {
		List<Segment> segments = new ArrayList<>();
		for (Segment segment : previous.segments()) {
			if (!segmentIndexes.contains(segment.index())) {
				segments.add(segment);
			}
		}
		segments.addAll(patch.segments());
		List<Gap> gaps = HypitReferenceAnalysis.coverageGaps(previous.durationSeconds(), segments, "未被检查覆盖");
		return new HypitReferenceAnalysis(previous.analysisId(), previous.mediaHash(), previous.durationSeconds(),
				previous.language(), previous.aspectRatio(), sortSegments(segments),
				mergeSystems(concat(previous.systems(), patch.systems())),
				sortEvents(concatEvents(previous.events(), patch.events())), gaps, patch.openQuestions(),
				gaps.isEmpty() ? HypitReferenceAnalysis.Status.SUCCEEDED : HypitReferenceAnalysis.Status.PROVISIONAL);
	}

	private static <T> List<T> concat(List<T> first, List<T> second) {
		List<T> all = new ArrayList<>(first);
		all.addAll(second);
		return all;
	}

	private static List<HypitReferenceAnalysis.Event> concatEvents(List<HypitReferenceAnalysis.Event> first,
			List<HypitReferenceAnalysis.Event> second) {
		List<HypitReferenceAnalysis.Event> all = new ArrayList<>(first);
		all.addAll(second);
		return all;
	}

	/** 持久进 C04 幂等命令（action=reference.analyze；同 operationId 幂等回读）。 */
	private Mono<Void> persist(String operationId, HypitReferenceAnalysis analysis) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("mediaHash", analysis.mediaHash());
		payload.put("operationId", operationId);
		String payloadJson = com.grassland.intelligence.hypit.project.HypitJson.write(payload);
		String payloadHash = com.grassland.intelligence.hypit.execution.HypitExternalExecutionBridge
				.sha256Hex(payloadJson);
		return commands.insert("system", "reference.analyze",
				UUID.nameUUIDFromBytes(operationId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				"analysis:" + analysis.mediaHash(), payloadHash, payloadJson, null).flatMap(accepted -> {
					if (accepted.existing()) {
						return Mono.empty();
					}
					Map<String, Object> result = new HashMap<>();
					result.put("analysisId", analysis.analysisId());
					result.put("status", analysis.status().name());
					result.put("analysisMarkdown", analysis.toAnalysisMarkdown());
					result.put("timelineMarkdown", analysis.toTimelineMarkdown());
					return commands.saveResult(accepted.row().id(), "succeeded",
							com.grassland.intelligence.hypit.project.HypitJson.write(result)).then();
				});
	}
}
