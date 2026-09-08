package com.grassland.marketplace.milestone;

import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 履约里程碑领域服务（任务书 #96 C96-02 / D96-03/D96-04）。
 *
 * <ul>
 *   <li><b>双方确认制</b>：提出方不能自签（SQL 守卫 proposed_by ≠ confirmed_by）；重复确认幂等回读。</li>
 *   <li><b>取消/超时补偿计算</b>（D96-04）：按「已确认里程碑 + 取消条款模板」折算——每类只认最高已确认版本、
 *       各类比例(bps)之和天然 ≤ 10000，总额再夹到已预留赏金内（补偿上限=已保障金额）。
 *       试点默认 20/60/20（§13：生产缺省比例待主程拍板，当前以可配缺省落地）。</li>
 *   <li><b>结算审计</b>：结算时把每类金额回填里程碑行（一次性），事件引用里程碑 id，全程可对账。</li>
 * </ul>
 *
 * <p>里程碑来源（事实派生，不新增 application.status）：提交发布凭证 → published 提案（submit 流程）；
 * 履约确认 → 商家联锁确认 pending published 提案（EngagementDecisionService.confirmWork）；
 * 脚本/成品里程碑由发布前审稿流（C96-04 草稿批准）写入——本服务只提供引擎与确认端点。
 */
@Component
public class EngagementMilestoneService {

    /** 取消/超时补偿折算结果：每类金额 + 引用的里程碑 id + 总额（已夹到预留内）。 */
    public record SettlementBreakdown(long scriptCents, long deliverableCents, long publishedCents,
                                      long totalCents, List<String> milestoneIds) {
        public static SettlementBreakdown none() {
            return new SettlementBreakdown(0, 0, 0, 0, List.of());
        }

        public boolean isEmpty() {
            return totalCents <= 0 || milestoneIds.isEmpty();
        }

        public Map<String, Object> toBody() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scriptCents", scriptCents);
            m.put("deliverableCents", deliverableCents);
            m.put("publishedCents", publishedCents);
            m.put("totalCents", totalCents);
            m.put("milestoneIds", milestoneIds);
            return m;
        }
    }

    private final EngagementMilestoneRepository milestones;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final int scriptBps;
    private final int deliverableBps;
    private final int publishedBps;

    public EngagementMilestoneService(EngagementMilestoneRepository milestones,
                                      OutboxRepository outbox,
                                      TransactionalOperator transactions,
                                      @Value("${marketplace.engagement.cancel-settlement-bps.script:2000}") int scriptBps,
                                      @Value("${marketplace.engagement.cancel-settlement-bps.deliverable:6000}") int deliverableBps,
                                      @Value("${marketplace.engagement.cancel-settlement-bps.published:2000}") int publishedBps) {
        this.milestones = milestones;
        this.outbox = outbox;
        this.transactions = transactions;
        this.scriptBps = clampBps(scriptBps);
        this.deliverableBps = clampBps(deliverableBps);
        this.publishedBps = clampBps(publishedBps);
    }

    private static int clampBps(int bps) {
        return Math.max(0, Math.min(10_000, bps));
    }

    /**
     * 里程碑对方确认（§6 POST /milestones/{mid}/confirm 领域侧）。资源级自查（caller 是报名任一方）由控制器
     * 完成后传入；这里守卫「确认人 ≠ 提出方」与里程碑属该报名。
     */
    public Mono<EngagementMilestone> confirm(Task task, TaskApplication app, Caller caller, String milestoneId) {
        return milestones.findById(milestoneId)
                .switchIfEmpty(fail(404, "里程碑不存在"))
                .flatMap(milestone -> {
                    if (!milestone.applicationId().equals(app.id())) {
                        return fail(404, "里程碑不存在");
                    }
                    if (milestone.confirmed()) {
                        return Mono.just(milestone);  // 幂等重入（TC96-010：确认后事实不可变）
                    }
                    if (milestone.proposedBy().equals(caller.accountId())) {
                        return fail(409, "双方确认制：需由对方确认");
                    }
                    return transactions.transactional(
                            milestones.confirm(milestoneId, caller.accountId())
                                    .switchIfEmpty(fail(409, "该里程碑已被对方确认或状态已变"))
                                    .flatMap(confirmed -> outbox.append(confirmedEnvelope(task, app, confirmed))
                                            .thenReturn(confirmed)));
                });
    }

    /**
     * 取消/超时补偿折算（D96-04）：每类取<b>最高已确认版本</b>一行，金额 = floor(预留赏金 × bps / 10000)，
     * 总额夹到预留赏金内（TC96-008 补偿 ≤ 已保障金额）。无确认里程碑 → 空（调用方走全额退现状）。
     */
    public Mono<SettlementBreakdown> computeSettlement(TaskApplication app) {
        long bounty = app.bountyCents();
        if (bounty <= 0) {
            return Mono.just(SettlementBreakdown.none());
        }
        return milestones.findByApplication(app.id())
                .filter(EngagementMilestone::confirmed)
                .collectList()
                .map(confirmed -> {
                    Map<String, EngagementMilestone> latestConfirmed = new LinkedHashMap<>();
                    for (EngagementMilestone row : confirmed) {
                        EngagementMilestone current = latestConfirmed.get(row.kind());
                        if (current == null || row.version() > current.version()) {
                            latestConfirmed.put(row.kind(), row);
                        }
                    }
                    long script = amountOf(latestConfirmed.get(EngagementMilestone.KIND_SCRIPT), bounty, scriptBps);
                    long deliverable =
                            amountOf(latestConfirmed.get(EngagementMilestone.KIND_DELIVERABLE), bounty, deliverableBps);
                    long published =
                            amountOf(latestConfirmed.get(EngagementMilestone.KIND_PUBLISHED), bounty, publishedBps);
                    List<String> ids = latestConfirmed.values().stream()
                            .filter(row -> switch (row.kind()) {
                                case EngagementMilestone.KIND_SCRIPT -> script > 0;
                                case EngagementMilestone.KIND_DELIVERABLE -> deliverable > 0;
                                case EngagementMilestone.KIND_PUBLISHED -> published > 0;
                                default -> false;
                            })
                            .map(EngagementMilestone::id)
                            .sorted()
                            .toList();
                    long capped = Math.min(bounty, script + deliverable + published);
                    return new SettlementBreakdown(script, deliverable, published, capped, ids);
                });
    }

    /** 结算审计回填：引用到的里程碑一次性落 amount_cents（事件与行双轨可对账）。 */
    public Mono<Void> recordSettlementAmounts(TaskApplication app, SettlementBreakdown breakdown) {
        if (breakdown.isEmpty()) {
            return Mono.empty();
        }
        return milestones.findByApplication(app.id()).collectList().flatMap(rows -> {
            List<Mono<Boolean>> writes = new java.util.ArrayList<>();
            for (EngagementMilestone row : rows) {
                if (!breakdown.milestoneIds().contains(row.id()) || !row.confirmed() || row.amountCents() != null) {
                    continue;
                }
                long amount = switch (row.kind()) {
                    case EngagementMilestone.KIND_SCRIPT -> breakdown.scriptCents();
                    case EngagementMilestone.KIND_DELIVERABLE -> breakdown.deliverableCents();
                    case EngagementMilestone.KIND_PUBLISHED -> breakdown.publishedCents();
                    default -> 0L;
                };
                writes.add(milestones.markSettledAmount(row.id(), amount));
            }
            if (writes.isEmpty()) {
                return Mono.empty();
            }
            return Mono.when(writes.toArray(Mono[]::new));
        }).then();
    }

    /**
     * 该报名是否有<b>已确认里程碑</b>（取消扫分支依据）：有 → 部分结算；无 → 全额退现状。
     */
    public Mono<Boolean> hasConfirmedMilestone(String applicationId) {
        return milestones.findByApplication(applicationId)
                .filter(EngagementMilestone::confirmed)
                .hasElements();
    }

    /** 该类最高已确认版本行的折算金额（未确认/无行 → 0）。 */
    private static long amountOf(EngagementMilestone latestConfirmed, long bounty, int bps) {
        return latestConfirmed == null ? 0L : bounty * bps / 10_000L;
    }

    /** 提案事件（提交发布凭证 → published 提案）。 */
    public Mono<EngagementMilestone> proposePublished(String applicationId, String submissionId, String proposedBy) {
        return milestones.nextVersion(applicationId, EngagementMilestone.KIND_PUBLISHED)
                .flatMap(version -> milestones.create(applicationId, EngagementMilestone.KIND_PUBLISHED, version,
                        submissionId, proposedBy));
    }

    /**
     * 履约确认联锁（EngagementDecisionService.confirmWork 调）：商家确认履约即对簿推荐的 published 提案
     * 完成对方互签（同一事务由调用方编排）。0 行 = 无 pending 提案（幂等）。
     */
    public Mono<Integer> confirmPendingPublished(String applicationId, String confirmedBy) {
        return milestones.findByApplication(applicationId)
                .filter(m -> EngagementMilestone.KIND_PUBLISHED.equals(m.kind()) && !m.confirmed()
                        && !confirmedBy.equals(m.proposedBy()))
                .flatMap(m -> milestones.confirm(m.id(), confirmedBy))
                .reduce(0, (count, ignored) -> count + 1);
    }

    /** 里程碑行 → 响应体（控制器共用装配）。 */
    public Map<String, Object> milestoneBody(EngagementMilestone milestone) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", milestone.id());
        m.put("applicationId", milestone.applicationId());
        m.put("kind", milestone.kind());
        m.put("version", milestone.version());
        m.put("evidenceSubmissionId", milestone.evidenceSubmissionId());
        m.put("proposedBy", milestone.proposedBy());
        m.put("confirmedBy", milestone.confirmedBy());
        m.put("confirmedAt", milestone.confirmedAt() == null ? null : milestone.confirmedAt().toString());
        m.put("amountCents", milestone.amountCents());
        m.put("createdAt", milestone.createdAt() == null ? null : milestone.createdAt().toString());
        return m;
    }

    private com.grassland.marketplace.event.EventEnvelope confirmedEnvelope(Task task, TaskApplication app,
            EngagementMilestone milestone) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.id());
        payload.put("applicationId", app.id());
        payload.put("recommenderAccountId", app.recommenderAccountId());
        payload.put("milestoneId", milestone.id());
        payload.put("kind", milestone.kind());
        payload.put("version", milestone.version());
        payload.put("proposedBy", milestone.proposedBy());
        payload.put("confirmedBy", milestone.confirmedBy());
        payload.put("taskOwnerId", task.ownerAccountId());
        String eventId = UUID.nameUUIDFromBytes(
                ("MilestoneConfirmed:" + milestone.id()).getBytes(StandardCharsets.UTF_8)).toString();
        return new com.grassland.marketplace.event.EventEnvelope(eventId, "MilestoneConfirmed", "TaskApplication",
                app.id(), 1, Instant.now(), null, payload);
    }

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new MarketplaceException(status, message));
    }
}
