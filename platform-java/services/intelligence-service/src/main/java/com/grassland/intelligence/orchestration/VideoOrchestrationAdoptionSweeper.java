package com.grassland.intelligence.orchestration;

import com.grassland.intelligence.videoproduction.VideoProductionTask;
import com.grassland.intelligence.videoproduction.VideoProductionTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 收养清扫（任务书 #66 卡A4）：开关切 temporal 后，为「仍在跑但没有在岗 workflow」的任务幂等
 * 补起 workflow——覆盖三类洞：①legacy 期创建的存量任务（翻开关时无人驱动）；②控制器起流失败
 * 被吞的行；③workflow 执行失败（activity 重试耗尽）后的重驱动。
 *
 * <p>幂等性：workflowId 唯一 + ALLOW_DUPLICATE_FAILED_ONLY，已在跑的吞 AlreadyStarted；
 * 已成功关闭的原发 workflow 重起会被拒（静默），重抽代次 r{n} 各自成流。行仍是真相源，
 * 收养只负责「有人开车」，不改变任何阶段语义。
 */
@Component
public class VideoOrchestrationAdoptionSweeper {

    private static final Logger log = LoggerFactory.getLogger(VideoOrchestrationAdoptionSweeper.class);
    private static final int BATCH_LIMIT = 200;

    private final VideoProductionTaskRepository tasks;
    private final VideoWorkflowStarter starter;
    private final VideoOrchestrationGate gate;

    public VideoOrchestrationAdoptionSweeper(VideoProductionTaskRepository tasks,
            VideoWorkflowStarter starter, VideoOrchestrationGate gate) {
        this.tasks = tasks;
        this.starter = starter;
        this.gate = gate;
    }

    @Scheduled(fixedDelayString = "${ai.video-production.orchestration-adopt-interval-ms:300000}")
    public void sweep() {
        if (!gate.temporal()) {
            return;
        }
        runOnce().subscribeOn(Schedulers.boundedElastic()).subscribe(
                adopted -> log.info("video orchestration adopted metric=video_orchestration_adopt "
                        + "adopted={}", adopted),
                error -> log.error("video orchestration adoption sweep failed", error));
    }

    /** 返回本次补起的 workflow 数（IT 直接断言）。 */
    public Mono<Long> runOnce() {
        return tasks.findNonTerminal(BATCH_LIMIT)
                .doOnNext(this::adopt)
                .count();
    }

    private void adopt(VideoProductionTask task) {
        // 首发流 + 各重抽代次都试一遍：已在跑/已收口的吞 AlreadyStarted，
        // 死掉的（失败终局或起流失败被吞）在这里复活。
        try {
            starter.startForTask(task, VideoTaskSpec.KIND_INITIAL, 0);
            for (int seq = 1; seq <= task.recomposeSeq(); seq++) {
                starter.startForTask(task, VideoTaskSpec.KIND_REROLL, seq);
            }
        } catch (RuntimeException error) {
            // 单行收养失败不阻塞批：下一拍再来
            log.warn("video orchestration adopt failed taskId={} cause={}", task.id(),
                    String.valueOf(error.getMessage()));
        }
    }
}
