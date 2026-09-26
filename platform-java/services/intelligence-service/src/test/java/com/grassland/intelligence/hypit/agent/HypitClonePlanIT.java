package com.grassland.intelligence.hypit.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.hypit.agent.HypitClonePlan.MaterialGap;
import com.grassland.intelligence.hypit.agent.HypitClonePlan.PlanStep;
import com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Evidence;
import com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Event;
import com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Segment;
import com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Status;
import com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.System;
import com.grassland.intelligence.hypit.job.HypitCommandRepository;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 克隆方案持久面（任务书 #107-2 C107-16 / TC107-16-01～04 持久部分）：真 PG 上 方案落 C04 幂等命令（同
 * requestId 幂等回读）、锚点越界 422、缺口 WAITING_INPUT 拒执行、分析未完成 409。真实构建执行复用 09 的
 * build.submit（本 IT 不重复）。
 */
class HypitClonePlanIT extends IntelligenceItSupport {

	@Autowired
	HypitClonePlanService plans;

	@Autowired
	HypitCommandRepository commands;

	private HypitReferenceAnalysis completeAnalysis() {
		return new HypitReferenceAnalysis("ra-c16-1", "b".repeat(64), 16.0, "zh", "9:16",
				List.of(new Segment(0, 0, 16, "全片", List.of(new Evidence("asset-x", 1.0, "锚点")))),
				List.of(new System("sys-ranking-1", "ranking", "榜单板", 0, 16, List.of("0"))),
				List.of(new Event("sfx", 13.0, null, "asset-audio", false)), List.of(), List.of(), Status.SUCCEEDED);
	}

	@Test
	void planPersistsIdempotentlyAndRejectsUnboundAnchors() {
		HypitReferenceAnalysis analysis = completeAnalysis();
		List<PlanStep> steps = List.of(new PlanStep(0, "ranking.render", "sys-ranking-1", 0.0, "榜单系统"));
		UUID requestId = UUID.randomUUID();
		HypitClonePlan plan = plans.save(UUID.randomUUID(), requestId, analysis, steps, List.of())
				.block(Duration.ofSeconds(20));
		assertThat(plan.status()).isEqualTo(HypitClonePlan.Status.READY);
		assertThat(plan.executable()).isTrue();

		HypitClonePlan replay = plans.save(UUID.randomUUID(), requestId, analysis, steps, List.of())
				.block(Duration.ofSeconds(20));
		assertThat(replay.planId()).as("同 requestId 幂等").isEqualTo(plan.planId());

		List<PlanStep> unbound = List.of(new PlanStep(0, "ranking.render", "sys-ghost", 0.0, "幽灵锚点"));
		IntelligenceException error = org.assertj.core.api.Assertions.catchThrowableOfType(() -> plans
				.save(UUID.randomUUID(), UUID.randomUUID(), analysis, unbound, List.of()).block(Duration.ofSeconds(20)),
				IntelligenceException.class);
		assertThat(error.code()).isEqualTo("hypit_clone_plan_unbound");
		assertThat(error.status()).isEqualTo(422);
	}

	@Test
	void materialGapsHoldExecutionAndIncompleteAnalysisIsRefused() {
		HypitReferenceAnalysis analysis = completeAnalysis();
		List<MaterialGap> gaps = List.of(new MaterialGap("material", "榜单背景音乐缺失", "asset-music"));
		HypitClonePlan waiting = plans
				.save(UUID.randomUUID(), UUID.randomUUID(), analysis,
						List.of(new PlanStep(0, "audio.mix", "sys-ranking-1", 0.0, "配乐")), gaps)
				.block(Duration.ofSeconds(20));
		assertThat(waiting.status()).isEqualTo(HypitClonePlan.Status.WAITING_INPUT);
		try {
			HypitClonePlan.assertExecutable(waiting);
			throw new AssertionError("expected 409 waiting_input");
		} catch (IntelligenceException error) {
			assertThat(error.code()).isEqualTo("hypit_clone_plan_waiting_input");
		}

		// 分析未完成全片 → 方案拒绝生成
		HypitReferenceAnalysis partial = new HypitReferenceAnalysis("ra-c16-2", "c".repeat(64), 16.0, null, "9:16",
				List.of(new Segment(0, 0, 8, "半片", List.of())), List.of(), List.of(), List.of(), List.of(),
				Status.PROVISIONAL);
		IntelligenceException refused = org.assertj.core.api.Assertions.catchThrowableOfType(() -> plans
				.save(UUID.randomUUID(), UUID.randomUUID(), partial,
						List.of(new PlanStep(0, "x", "sys-ranking-1", 0.0, "y")), List.of())
				.block(Duration.ofSeconds(20)), IntelligenceException.class);
		assertThat(refused.code()).isEqualTo("hypit_analysis_incomplete");
	}
}
