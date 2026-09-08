package com.grassland.marketplace.benefit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
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
 * 体验权益领域服务（任务书 #96 C96-03 / D96-05）。
 *
 * <ul>
 *   <li><b>预约/兑现</b>：推荐官 book（待预约→已预约，带体验项目与预约时段）→ fulfill（主张已到店/收样）→
 *       商家 confirm（兑现确认）；未兑现前任一方可取消。</li>
 *   <li><b>商家失约</b>：推荐官举证主张（default-claim）→ 商家限时回应（默认 72h 可配）——到期未回应由
 *       {@code BenefitDefaultDispatcher} 自动成立：押金全退 + 交付计时暂停 + 可无责退出；限时否认则继续履约。</li>
 *   <li><b>计时暂停可解释</b>（TC96-012）：成立时交付/补救截止整体顺延回应窗秒数，暂停区间
 *       [claimedAt, resolvedAt] 落权益行可查，顺延量 = 回应窗长，可对账。</li>
 *   <li><b>未消费/已消费分开</b>（TC96-014）：未消费失约成立 → 押金全退 + 可无责退出；已兑现后推荐官不履约
 *       → 不走失约路径，押金按交付超时语义判商家（C96-01 freebieCompensate）。</li>
 * </ul>
 *
 * <p><b>成立收敛次序</b>（崩溃安全）：tx[guarded 成立 + 截止顺延 + outbox] → 押金退还（finance 幂等，
 * 事务外）。押金腿在派发器里对 defaulted 行持续重放（freebieRefund 404/409 视作成功）直至 finance 终态，
 * 避免出现「已成立未退款」的静默缺口；否认与成立经 default_resolved_at 单边胜出，退款只在成立后发生。
 */
@Component
public class ExperienceBenefitService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExperienceBenefitRepository benefits;
    private final TaskApplicationRepository apps;
    private final TaskRepository tasks;
    private final OutboxRepository outbox;
    private final FinanceEscrowClient finance;
    private final TransactionalOperator transactions;
    private final long responseWindowSeconds;

    public ExperienceBenefitService(ExperienceBenefitRepository benefits,
                                    TaskApplicationRepository apps,
                                    TaskRepository tasks,
                                    OutboxRepository outbox,
                                    FinanceEscrowClient finance,
                                    TransactionalOperator transactions,
                                    @Value("${marketplace.engagement.benefit-response-hours:72}") long responseHours) {
        this.benefits = benefits;
        this.apps = apps;
        this.tasks = tasks;
        this.outbox = outbox;
        this.finance = finance;
        this.transactions = transactions;
        this.responseWindowSeconds = Math.max(1, responseHours * 3600L);
    }

    public long responseWindowSeconds() {
        return responseWindowSeconds;
    }

    /** 权益单读取（解耦展示）：benefit 可为 null（未建单），押金快照单独携带。 */
    public Mono<Map<String, Object>> benefitView(TaskApplication app) {
        return benefits.findByApplication(app.id()).<Map<String, Object>>map(benefit -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("benefit", toBody(benefit));
            m.put("freebieDepositCents", app.freebieDepositCents());
            return m;
        }).defaultIfEmpty(new LinkedHashMap<>(Map.of("freebieDepositCents", app.freebieDepositCents())));
    }

    /** 预约（推荐官）：权益行惰性建单（体验任务首触即建）。 */
    public Mono<ExperienceBenefit> book(Task task, TaskApplication app, List<String> items, Instant bookingWindow) {
        requireFreebie(app);
        String itemsJson;
        try {
            itemsJson = MAPPER.writeValueAsString(items == null ? List.of() : items);
        } catch (Exception e) {
            return fail(400, "体验项目格式无效");
        }
        return benefits.upsertBooking(app.id(), task.storeId(), itemsJson, bookingWindow)
                .switchIfEmpty(fail(409, "当前权益状态不可预约"))
                .flatMap(booked -> outbox.append(envelope("BenefitBooked", task, app, booked)).thenReturn(booked));
    }

    /** 主张兑现（推荐官）：booked → fulfilled。 */
    public Mono<ExperienceBenefit> fulfill(Task task, TaskApplication app) {
        requireFreebie(app);
        return benefits.markFulfilled(app.id())
                .switchIfEmpty(fail(409, "当前权益状态不可主张兑现"))
                .flatMap(fulfilled -> outbox.append(envelope("BenefitFulfilled", task, app, fulfilled))
                        .thenReturn(fulfilled));
    }

    /** 兑现确认（商家，幂等）。 */
    public Mono<ExperienceBenefit> confirmFulfillment(Task task, TaskApplication app, Caller merchant) {
        requireFreebie(app);
        return benefits.confirmFulfillment(app.id(), merchant.accountId())
                .switchIfEmpty(fail(409, "当前权益状态不可确认兑现"))
                .flatMap(confirmed -> outbox.append(envelope("BenefitFulfillmentConfirmed", task, app, confirmed))
                        .thenReturn(confirmed));
    }

    /** 取消（双方，未兑现前）。 */
    public Mono<ExperienceBenefit> cancel(Task task, TaskApplication app) {
        requireFreebie(app);
        return benefits.cancel(app.id())
                .switchIfEmpty(fail(409, "当前权益状态不可取消"))
                .flatMap(cancelled -> outbox.append(envelope("BenefitCancelled", task, app, cancelled))
                        .thenReturn(cancelled));
    }

    /**
     * 商家失约主张（§6 POST /benefit/default-claim，推荐官举证发起）：booked + 未主张 →
     * 设回应窗（默认 72h，可配）。
     */
    public Mono<ExperienceBenefit> claimDefault(Task task, TaskApplication app) {
        requireFreebie(app);
        return benefits.claimDefault(app.id(), responseWindowSeconds)
                .switchIfEmpty(fail(409, "当前权益状态不可发起失约主张"))
                .flatMap(claimed -> outbox.append(envelope("BenefitDefaultClaimed", task, app, claimed))
                        .thenReturn(claimed));
    }

    /** 商家限时回应（否认）：继续履约，押金不动。 */
    public Mono<ExperienceBenefit> respondDefaultDenied(Task task, TaskApplication app) {
        requireFreebie(app);
        return benefits.respondDefaultDenied(app.id())
                .switchIfEmpty(fail(409, "无待回应的失约主张"))
                .flatMap(denied -> outbox.append(envelope("BenefitDefaultDenied", task, app, denied))
                        .thenReturn(denied));
    }

    /**
     * 失约自动成立（派发器扫描到点调用；guarded 单边胜出）：merchant_defaulted + 截止顺延回应窗 +
     * 事件同一事务。返回 null 行 = 已成立/被否认/未到点（调用方幂等跳过）。
     */
    public Mono<ExperienceBenefit> establishDefault(String applicationId) {
        return apps.findById(applicationId)
                .flatMap(app -> tasks.findById(app.taskId())
                        .switchIfEmpty(Mono.error(new IllegalStateException("task missing for benefit " + app.id())))
                        .flatMap(task -> transactions.transactional(
                                benefits.establishDefault(app.id())
                                        .flatMap(benefit -> apps
                                                .extendDeliveryDeadline(app.id(), app.taskId(),
                                                        responseWindowSeconds)
                                                .switchIfEmpty(Mono.empty())
                                                .then(outbox.append(envelope(
                                                        "BenefitDefaultEstablished", task, app, benefit)))
                                                .thenReturn(benefit)))));
    }

    /** 成立后的押金全退腿（TC96-013）：finance 幂等（404/409 视作成功），派发器可安全重放。 */
    public Mono<Void> refundDefaultedDeposit(TaskApplication app) {
        if (app.freebieDepositCents() <= 0) {
            return Mono.empty();
        }
        return tasks.findById(app.taskId())
                .flatMap(task -> finance.freebieRefund(task.organizationId(), app.id()))
                .switchIfEmpty(Mono.empty());
    }

    /** 失约成立标记（无责退出前置）：该报名的权益单是否已成立商家失约。 */
    public Mono<Boolean> defaultEstablished(String applicationId) {
        return benefits.findByApplication(applicationId)
                .map(benefit -> ExperienceBenefit.STATUS_MERCHANT_DEFAULTED.equals(benefit.status()))
                .defaultIfEmpty(false);
    }

    /** 里程碑/权益行 → 响应体（控制器共用装配）。 */
    public Map<String, Object> benefitBody(ExperienceBenefit benefit) {
        return toBody(benefit);
    }

    private void requireFreebie(TaskApplication app) {
        if (!app.isFreebie()) {
            throw new MarketplaceException(409, "非体验押金任务，无权益单");
        }
    }

    private Map<String, Object> toBody(ExperienceBenefit benefit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", benefit.id());
        m.put("applicationId", benefit.applicationId());
        m.put("storeId", benefit.storeId());
        m.put("status", benefit.status());
        m.put("bookingWindow", benefit.bookingWindow() == null ? null : benefit.bookingWindow().toString());
        m.put("items", parseItems(benefit.itemsJson()));
        m.put("fulfilledAt", benefit.fulfilledAt() == null ? null : benefit.fulfilledAt().toString());
        m.put("fulfilledConfirmed", benefit.fulfilledConfirmedBy() != null);
        m.put("defaultClaimedAt", benefit.defaultClaimedAt() == null ? null : benefit.defaultClaimedAt().toString());
        if (benefit.defaultClaimOpen()) {
            m.put("defaultDeadlineAt",
                    benefit.defaultDeadlineAt() == null ? null : benefit.defaultDeadlineAt().toString());
        }
        if (benefit.defaultResolvedAt() != null) {
            m.put("defaultResolvedAt", benefit.defaultResolvedAt().toString());
            m.put("defaultResolution", benefit.defaultResolution());
        }
        m.put("createdAt", benefit.createdAt() == null ? null : benefit.createdAt().toString());
        return m;
    }

    private Object parseItems(String itemsJson) {
        try {
            return MAPPER.readValue(itemsJson == null ? "[]" : itemsJson, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private EventEnvelope envelope(String eventType, Task task, TaskApplication app, ExperienceBenefit benefit) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.id());
        payload.put("applicationId", app.id());
        payload.put("organizationId", task.organizationId());
        payload.put("recommenderAccountId", app.recommenderAccountId());
        payload.put("benefitId", benefit.id());
        payload.put("benefitStatus", benefit.status());
        if (benefit.defaultResolution() != null) {
            payload.put("defaultResolution", benefit.defaultResolution());
        }
        payload.put("taskOwnerId", task.ownerAccountId());
        String eventId = UUID.nameUUIDFromBytes(
                (eventType + ":" + benefit.id() + ":" + benefit.status()
                        + (benefit.defaultResolution() == null ? "" : ":" + benefit.defaultResolution()))
                        .getBytes(StandardCharsets.UTF_8)).toString();
        return new EventEnvelope(eventId, eventType, "TaskApplication", app.id(), 1, Instant.now(), null, payload);
    }

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new MarketplaceException(status, message));
    }
}
