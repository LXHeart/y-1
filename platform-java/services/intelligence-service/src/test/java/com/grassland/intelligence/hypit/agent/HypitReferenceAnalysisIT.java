package com.grassland.intelligence.hypit.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Evidence;
import com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Event;
import com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Segment;
import com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Status;
import com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.System;
import com.grassland.intelligence.hypit.job.HypitCommandRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ReferenceAnalysis 持久面（任务书 #107-2 C107-15 / semantic-reference 固定预期）： 榜单系统跨 3
 * 镜头合并为稳定 systemId、reveal/sfx/评论卡事件保留出处、 coverage 全覆盖才 SUCCEEDED、同 operationId
 * 幂等、增量重分析保留未动段证据。 LLM 回放（确定性 fixture）与真实模型分开标记——真实 LLM REAL_NOT_RUN。
 */
class HypitReferenceAnalysisIT extends IntelligenceItSupport {

	@Autowired
	HypitReferenceAnalysisService service;

	@Autowired
	HypitCommandRepository commands;

	private static final String HASH = "a".repeat(64);

	private HypitReferenceAnalysisService.AnalysisInput fixtureInput() {
		List<Segment> segments = List.of(
				new Segment(0, 0, 4, "开场：榜单镜头 A", List.of(new Evidence("asset-frame-0", 0.5, "榜单完整可见"))),
				new Segment(1, 4, 8, "词触发 reveal：\"揭晓\"", List.of(new Evidence("asset-frame-1", 5.2, "reveal 动画"))),
				new Segment(2, 8, 12, "B-roll 独立区间", List.of(new Evidence("asset-broll", 9.0, "B-roll 画面"))),
				new Segment(3, 12, 16, "榜单回归 + 音效 + 评论卡", List.of(new Evidence("asset-frame-3", 13.0, "榜单 + sfx"))));
		List<System> systems = List.of(new System("sys-ranking-1", "ranking", "榜单板", 0, 4, List.of("0")),
				new System("sys-ranking-2", "ranking", "榜单板", 12, 16, List.of("3")),
				new System("sys-broll-1", "broll", "B-roll", 8, 12, List.of("2")));
		List<Event> events = List.of(new Event("reveal", 5.2, "揭晓", "asset-frame-1", false),
				new Event("sfx", 13.0, null, "asset-audio-3", false),
				new Event("comment-card", 14.0, null, "asset-frame-3", false));
		return new HypitReferenceAnalysisService.AnalysisInput("op-" + UUID.randomUUID(), HASH, 16.0, "zh", "9:16",
				true, segments, systems, events, List.of());
	}

	@Test
	void coverageSystemsAndEventsFollowTheFixtureExpectations() {
		HypitReferenceAnalysis analysis = service.analyze(fixtureInput()).block(Duration.ofSeconds(20));
		// 全覆盖：4 段并集 == [0,16)，无 gap → SUCCEEDED
		assertThat(analysis.status()).isEqualTo(Status.SUCCEEDED);
		assertThat(analysis.gaps()).isEmpty();
		// 跨切镜榜单合并为稳定 systemId（1、3 段），事件不丢
		System ranking = analysis.systems().stream().filter(system -> system.kind().equals("ranking")).findFirst()
				.orElseThrow();
		assertThat(ranking.systemId()).isEqualTo("sys-ranking-1");
		assertThat(ranking.segmentIndexes()).containsExactly("0", "3");
		assertThat(ranking.firstSeenSeconds()).isEqualTo(0.0);
		assertThat(ranking.lastSeenSeconds()).isEqualTo(16.0);
		assertThat(analysis.events()).extracting(Event::kind).containsExactly("reveal", "sfx", "comment-card");
		// 可读说明生成且观察/推断分列
		assertThat(analysis.toAnalysisMarkdown()).contains("## Systems", "trigger=`揭晓`", "(observed)");
	}

	@Test
	void uncoveredReaderStaysProvisionalAndUnreadyTranscriptionWaits() {
		HypitReferenceAnalysisService.AnalysisInput partial = new HypitReferenceAnalysisService.AnalysisInput(
				"op-partial-" + UUID.randomUUID(), HASH, 16.0, null, "9:16", true,
				List.of(new Segment(0, 0, 8, "只查了前半", List.of())), List.of(), List.of(), List.of());
		HypitReferenceAnalysis provisional = service.analyze(partial).block(Duration.ofSeconds(20));
		assertThat(provisional.status()).as("未检查全片不得标 SUCCEEDED").isEqualTo(Status.PROVISIONAL);
		assertThat(provisional.gaps()).hasSize(1);
		assertThat(provisional.gaps().get(0).startSeconds()).isEqualTo(8.0);

		HypitReferenceAnalysisService.AnalysisInput noTranscript = new HypitReferenceAnalysisService.AnalysisInput(
				"op-muted-" + UUID.randomUUID(), HASH, 16.0, null, "9:16", false,
				List.of(new Segment(0, 0, 16, "静音片：视觉全查", List.of())), List.of(), List.of(), List.of("音频缺失，音效类事件无法确认"));
		HypitReferenceAnalysis waiting = service.analyze(noTranscript).block(Duration.ofSeconds(20));
		assertThat(waiting.status()).as("转写失败时视觉分析仍可进行并如实 waiting").isEqualTo(Status.WAITING_INPUT);
		assertThat(waiting.openQuestions()).isNotEmpty();
	}

	@Test
	void sameOperationIdIsIdempotentAndIncrementalKeepsPriorEvidence() {
		HypitReferenceAnalysisService.AnalysisInput input = fixtureInput();
		HypitReferenceAnalysis first = service.analyze(input).block(Duration.ofSeconds(20));
		HypitReferenceAnalysis replay = service.analyze(input).block(Duration.ofSeconds(20));
		assertThat(replay.analysisId()).as("稳定 operation 幂等（不重复落 analysis）").isEqualTo(first.analysisId());

		// 增量：只重查段 1，其余段证据原样保留
		HypitReferenceAnalysisService.AnalysisInput patch = new HypitReferenceAnalysisService.AnalysisInput("op-patch",
				HASH, 16.0, "zh", "9:16", true,
				List.of(new Segment(1, 4, 8, "重查：reveal 精确到帧",
						List.of(new Evidence("asset-frame-1b", 5.4, "更准的 reveal 证据")))),
				List.of(), List.of(), List.of());
		HypitReferenceAnalysis updated = service.incremental(first, patch, List.of(1));
		assertThat(updated.segments()).hasSize(4);
		assertThat(updated.segments().stream().filter(segment -> segment.index() == 1).findFirst().orElseThrow()
				.evidence().get(0).assetId()).isEqualTo("asset-frame-1b");
		assertThat(updated.segments().stream().filter(segment -> segment.index() == 0).findFirst().orElseThrow()
				.evidence().get(0).assetId()).as("未动段证据保留").isEqualTo("asset-frame-0");
		assertThat(updated.status()).isEqualTo(Status.SUCCEEDED);
	}
}
