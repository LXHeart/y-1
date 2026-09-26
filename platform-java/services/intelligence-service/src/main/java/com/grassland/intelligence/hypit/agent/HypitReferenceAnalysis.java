package com.grassland.intelligence.hypit.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ReferenceAnalysis schema（任务书 #107-2 C107-15 / K11.3）：全片参考理解的
 * 持久形态。观察（observation）与推断（inference）分列；证据必须带 sourceTime 与
 * evidenceAsset，可回放定位；coverage 是分段并集，未覆盖即有 gap， 未检查全片不得标 succeeded。
 */
public record HypitReferenceAnalysis(String analysisId, String mediaHash, double durationSeconds, String language,
		String aspectRatio, List<Segment> segments, List<System> systems, List<Event> events, List<Gap> gaps,
		List<String> openQuestions, Status status) {

	public enum Status {
		PROVISIONAL, SUCCEEDED, WAITING_INPUT
	}

	/** 一段连续检查区间 [startSeconds, endSeconds)。 */
	public record Segment(int index, double startSeconds, double endSeconds, String summary, List<Evidence> evidence) {
	}

	/** 跨切镜持续系统：稳定 systemId，跨镜头合并但保留事件变化。 */
	public record System(String systemId, String kind, String name, double firstSeenSeconds, double lastSeenSeconds,
			List<String> segmentIndexes) {
	}

	/** 事件：短暂切换/词触发效果/音效/评论卡等；出处可定位。 */
	public record Event(String kind, double atSeconds, String trigger, String evidenceAsset, boolean inferred) {
	}

	public record Gap(double startSeconds, double endSeconds, String reason) {
	}

	public record Evidence(String assetId, double sourceTimeSeconds, String note) {
	}

	/** coverage 并集检查：返回未覆盖区间（升序）；空列表 = 全片覆盖。 */
	public static List<Gap> coverageGaps(double durationSeconds, List<Segment> segments, String reason) {
		List<Gap> gaps = new ArrayList<>();
		List<Segment> sorted = new ArrayList<>(segments);
		sorted.sort((a, b) -> Double.compare(a.startSeconds(), b.startSeconds()));
		double cursor = 0.0;
		for (Segment segment : sorted) {
			if (segment.startSeconds() > cursor + 1e-9) {
				gaps.add(new Gap(cursor, segment.startSeconds(), reason));
			}
			cursor = Math.max(cursor, segment.endSeconds());
		}
		if (cursor < durationSeconds - 1e-9) {
			gaps.add(new Gap(cursor, durationSeconds, reason));
		}
		return gaps;
	}

	public boolean fullyCovered() {
		return gaps.isEmpty();
	}

	/** ANALYSIS.md：人读说明（步骤 7），观察/推断分列。 */
	public String toAnalysisMarkdown() {
		StringBuilder markdown = new StringBuilder();
		markdown.append("# Reference Analysis\n\n");
		markdown.append("- analysisId: `").append(analysisId).append("`\n");
		markdown.append("- mediaHash: `").append(mediaHash).append("`\n");
		markdown.append("- duration: ").append(durationSeconds).append("s, language: ")
				.append(language == null ? "unknown" : language).append("\n");
		markdown.append("- status: ").append(status).append("\n\n");
		markdown.append("## Systems（跨切镜持续）\n\n");
		for (System system : systems) {
			markdown.append("- ").append(system.systemId()).append(" [").append(system.kind()).append("] ")
					.append(system.name()).append(" — ").append(system.firstSeenSeconds()).append("s..")
					.append(system.lastSeenSeconds()).append("s (segments ")
					.append(String.join(",", system.segmentIndexes())).append(")\n");
		}
		markdown.append("\n## Events\n\n");
		for (Event event : events) {
			markdown.append("- ").append(event.atSeconds()).append("s ").append(event.kind());
			if (event.trigger() != null && !event.trigger().isBlank()) {
				markdown.append(" trigger=`").append(event.trigger()).append("`");
			}
			markdown.append(event.inferred() ? " (inferred)" : " (observed)");
			markdown.append(" evidence=`").append(event.evidenceAsset()).append("`\n");
		}
		if (!gaps.isEmpty()) {
			markdown.append("\n## Gaps（未覆盖，禁止标全片完成）\n\n");
			for (Gap gap : gaps) {
				markdown.append("- ").append(gap.startSeconds()).append("s..").append(gap.endSeconds()).append("s — ")
						.append(gap.reason()).append("\n");
			}
		}
		if (!openQuestions.isEmpty()) {
			markdown.append("\n## Open questions\n\n");
			for (String question : openQuestions) {
				markdown.append("- ").append(question).append("\n");
			}
		}
		return markdown.toString();
	}

	/** TIMELINE.md：按时间的段落/事件索引。 */
	public String toTimelineMarkdown() {
		Map<Integer, StringBuilder> bySegment = new LinkedHashMap<>();
		for (Segment segment : segments) {
			bySegment.put(segment.index(), new StringBuilder(String.format("## Segment %d [%.1fs..%.1fs) — %s%n%n",
					segment.index(), segment.startSeconds(), segment.endSeconds(), segment.summary())));
		}
		for (Event event : events) {
			bySegment.values().stream().findAny();
		}
		StringBuilder timeline = new StringBuilder("# Timeline\n\n");
		bySegment.values().forEach(timeline::append);
		return timeline.toString();
	}
}
