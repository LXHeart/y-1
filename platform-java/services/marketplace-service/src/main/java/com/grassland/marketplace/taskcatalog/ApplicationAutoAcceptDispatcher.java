package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.reputation.ReputationService;
import com.grassland.marketplace.reputation.ReputationSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 任务书 #27：自动通过 dispatcher。镜像 {@link TaskReviewSlaDispatcher} 结构。
 *
 * <p>每轮扫描已发布且配置了 {@code auto_accept_min_level} 的任务，对其 pending 报名按声誉权重排序，
 * 逐条判定推荐官等级是否达标，满足则调共享 accept 内核接受。
 *
 * <p>冷却机制：资金不足导致 Saga 补偿回 pending 的报名，跳过 N 秒避免空转派发 Saga。
 */
@Component
public class ApplicationAutoAcceptDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ApplicationAutoAcceptDispatcher.class);

    private final TaskRepository tasks;
    private final TaskApplicationRepository apps;
    private final ApplicationController acceptController;
    private final ReputationService reputationService;
    private final boolean enabled;
    private final int scanLimit;

    /** 冷却记录：applicationId → 上次补偿回退的时刻。跳过冷却窗口内的重复派发。 */
    private final ConcurrentHashMap<String, Instant> compensatedCooldown = new ConcurrentHashMap<>();
    private static final long COOLDOWN_SECONDS = 60;

    public ApplicationAutoAcceptDispatcher(
            TaskRepository tasks, TaskApplicationRepository apps, ApplicationController acceptController,
            ReputationService reputationService,
            @Value("${marketplace.applications.auto-accept-enabled:true}") boolean enabled,
            @Value("${marketplace.applications.auto-accept-scan-limit:50}") int scanLimit) {
        this.tasks = tasks;
        this.apps = apps;
        this.acceptController = acceptController;
        this.reputationService = reputationService;
        this.enabled = enabled;
        this.scanLimit = Math.max(1, Math.min(scanLimit, 200));
    }

    @Scheduled(fixedDelayString = "${marketplace.applications.auto-accept-poll-ms:5000}")
    public void dispatch() {
        if (!enabled) return;
        cleanCooldown();
        processTasks().subscribe(
                count -> { if (count > 0) log.info("auto-accept dispatcher processed {} task(s)", count); },
                error -> log.error("auto-accept dispatcher failed", error));
    }

    Mono<Integer> processTasks() {
        // 开关守卫放这里（而非只在 dispatch）：保证任何调用路径（含测试/运维手动触发）都 fail-safe。
        if (!enabled) return Mono.just(0);
        return tasks.findAutoAcceptEnabled(scanLimit)
                .concatMap(this::processTask)
                .reduce(0, Integer::sum);
    }

    private Mono<Integer> processTask(Task task) {
        int minLevel = task.autoAcceptMinLevel();
        // 查该任务的 pending 报名
        return apps.findByTaskId(task.id(), "pending", null, null, 200)
                .collectList()
                .flatMap(pendingApps -> {
                    if (pendingApps.isEmpty()) return Mono.just(0);
                    // 按声誉权重排序（复用 ApplicationController 的排序逻辑）
                    return reputationService.snapshots(
                                    pendingApps.stream().map(TaskApplication::recommenderAccountId).toList())
                            .map(snapshots -> pendingApps.stream()
                                    .map(app -> Map.entry(app, snapshots.get(app.recommenderAccountId())))
                                    .sorted((a, b) -> {
                                        int weightA = a.getValue().evaluation().taskPriorityWeight();
                                        int weightB = b.getValue().evaluation().taskPriorityWeight();
                                        return Integer.compare(weightB, weightA);
                                    })
                                    .toList())
                            .flatMap(entries -> processEntries(task, minLevel, entries));
                })
                .thenReturn(1)
                .onErrorResume(error -> {
                    log.warn("auto-accept task={} failed reason={}", task.id(), error.getMessage());
                    return Mono.just(0);
                });
    }

    private Mono<Integer> processEntries(Task task, int minLevel,
                                         List<Map.Entry<TaskApplication, ReputationSnapshot>> entries) {
        return Flux.fromIterable(entries)
                .concatMap(entry -> {
                    TaskApplication app = entry.getKey();
                    ReputationSnapshot snapshot = entry.getValue();
                    int level = snapshot.evaluation().effectiveLevel().number();

                    // 等级不达标 → 跳过
                    if (level < minLevel) {
                        return Mono.<String>empty();
                    }

                    // 冷却检查（资金不足补偿回退后跳过）
                    Instant lastCompensated = compensatedCooldown.get(app.id());
                    if (lastCompensated != null
                            && lastCompensated.plusSeconds(COOLDOWN_SECONDS).isAfter(Instant.now())) {
                        return Mono.<String>empty();
                    }

                    // 达标 → 调共享 accept 内核
                    return acceptController.acceptForDispatcher(task, app, snapshot)
                            .map(outcome -> {
                                if ("compensated".equals(outcome)) {
                                    compensatedCooldown.put(app.id(), Instant.now());
                                }
                                return outcome;
                            })
                            // 单条失败只跳过该条，不终止同任务其它报名。
                            .onErrorResume(e -> {
                                log.warn("auto-accept app={} failed reason={}", app.id(), e.getMessage());
                                return Mono.empty();
                            });
                })
                // 名额满 → 停止该任务本轮（takeUntil：发出 slots_full 后完成流，
                // 剩余 pending 不再空转 claim；下轮扫描名额释放后自然恢复）。
                .takeUntil(outcome -> "slots_full".equals(outcome))
                .collectList()
                .map(List::size);
    }

    /** 清理过期的冷却记录。 */
    private void cleanCooldown() {
        Instant cutoff = Instant.now().minusSeconds(COOLDOWN_SECONDS * 2);
        compensatedCooldown.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
    }
}
