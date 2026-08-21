package com.grassland.intelligence.ai.run;

import com.grassland.intelligence.ai.run.AiBudgetAlertRepository.AiBudgetAlert;
import com.grassland.intelligence.ai.run.AiBudgetAlertRepository.Level;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 组织 AI 预算阈值告警扫描器（任务书 #37 登记项）。
 *
 * <p>周期扫描组织全局预算行，四个规则维度（日/月 × tokens/cents）各自两条阈值线：
 * {@code warning}（默认用量达上限 80%，可配）与 {@code exceeded}（超上限，与预算管理
 * {@code overCurrentUsage} 同口径严格大于）。只在<b>等级跃迁</b>时发一次事件——
 * {@code ai_budget_alert} 行 + 确定性 eventId 双闸保证同窗同等级至多一条；换窗
 * （period_key 变化）自然重置。上限未配置（null=不限）的维度不告警。
 *
 * <p>事件经 outbox 发 {@code grassland.intelligence.events}，由 identity 通知消费端
 * 转站内通知 + 邮件给组织 owner/admin（WALLET 类）。
 */
@Component
@ConditionalOnProperty(prefix = "intelligence.budget-alert", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BudgetAlertScanner {

    private static final Logger log = LoggerFactory.getLogger(BudgetAlertScanner.class);

    private final AiModelBudgetRepository budgets;
    private final AiBudgetAlertRepository alerts;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final int warnThresholdPercent;

    public BudgetAlertScanner(
            AiModelBudgetRepository budgets,
            AiBudgetAlertRepository alerts,
            OutboxRepository outbox,
            TransactionalOperator transactions,
            @Value("${intelligence.budget-alert.warn-threshold-percent:80}") int warnThresholdPercent) {
        this.budgets = budgets;
        this.alerts = alerts;
        this.outbox = outbox;
        this.transactions = transactions;
        this.warnThresholdPercent = warnThresholdPercent;
    }

    @Scheduled(fixedDelayString = "${intelligence.budget-alert.poll-interval-ms:60000}")
    public void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        scanOnce()
                .doOnError(error -> log.error("budget alert scan failed", error))
                .doFinally(signal -> polling.set(false))
                .subscribe();
    }

    Flux<Void> scanOnce() {
        LocalDate today = LocalDate.now();
        String dailyKey = today.toString();
        String monthlyKey = YearMonth.from(today).toString();
        return budgets.listOrganizationBudgetRows()
                .concatMap(budget -> Flux.concat(
                        check(budget, "daily_tokens", "daily", "tokens", dailyKey,
                                effectiveDailyTokens(budget), budget.maxTokensDaily()),
                        check(budget, "daily_cents", "daily", "cents", dailyKey,
                                effectiveDailyCents(budget), budget.maxCentsDaily()),
                        check(budget, "monthly_tokens", "monthly", "tokens", monthlyKey,
                                effectiveMonthlyTokens(budget), budget.maxTokensMonthly()),
                        check(budget, "monthly_cents", "monthly", "cents", monthlyKey,
                                effectiveMonthlyCents(budget), budget.maxCentsMonthly())));
    }

    /**
     * 单维度检查：limit 未配置直接跳过；计算当前等级；相对既有告警行只在
     * 「跃迁到更高等级」时同事务 upsert + append 事件。
     *
     * <p>压制判定必须经 map(boolean)+defaultIfEmpty 落定后再分支——若用
     * {@code flatMap(Mono.empty()).switchIfEmpty(transition)}，压制路径的空完成会
     * 误触发 switchIfEmpty（Reactor 空完成陷阱），导致降级场景重复发事件。
     */
    private Mono<Void> check(
            AiModelBudget budget, String ruleKey, String window, String unit, String periodKey,
            long usage, Long limit) {
        if (limit == null || limit <= 0) {
            return Mono.empty();
        }
        Level current = levelFor(usage, limit);
        if (current == null) {
            return Mono.empty();
        }
        return Mono.defer(() -> alerts.find(budget.organizationId(), ruleKey, periodKey)
                .map(existing -> rank(existing.level()) >= rank(current))
                .defaultIfEmpty(false)
                .flatMap(suppressed -> suppressed
                        ? Mono.empty()
                        : transition(budget, ruleKey, window, unit, periodKey, usage, limit, current)));
    }

    private Mono<Void> transition(
            AiModelBudget budget, String ruleKey, String window, String unit, String periodKey,
            long usage, long limit, Level level) {
        return transactions.transactional(
                alerts.upsert(budget.organizationId(), ruleKey, periodKey, level, usage, limit)
                        .then(outbox.append(envelope(budget.organizationId(), ruleKey, window, unit,
                                periodKey, usage, limit, level))))
                .then(Mono.fromRunnable(() -> log.info(
                        "Budget alert {} for org={} rule={} period={} usage={} limit={}",
                        level, budget.organizationId(), ruleKey, periodKey, usage, limit)));
    }

    /** exceeded 严格大于（与 overCurrentUsage 同口径）；warning 达到百分比即触发。 */
    private Level levelFor(long usage, long limit) {
        if (usage > limit) {
            return Level.EXCEEDED;
        }
        if (usage * 100L >= limit * (long) warnThresholdPercent) {
            return Level.WARNING;
        }
        return null;
    }

    private static int rank(Level level) {
        return level == Level.EXCEEDED ? 2 : 1;
    }

    private static EventEnvelope envelope(
            String organizationId, String ruleKey, String window, String unit, String periodKey,
            long usage, long limit, Level level) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", organizationId);
        payload.put("ruleKey", ruleKey);
        payload.put("level", level.dbValue());
        payload.put("window", window);
        payload.put("unit", unit);
        payload.put("periodKey", periodKey);
        payload.put("usage", usage);
        payload.put("limit", limit);
        String deterministicId = UUID.nameUUIDFromBytes(
                ("AiBudgetAlert:" + organizationId + ":" + ruleKey + ":" + periodKey + ":" + level.dbValue())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return new EventEnvelope(
                deterministicId,
                "AiOrgBudgetThresholdCrossed",
                "AiModelBudget",
                organizationId,
                1,
                Instant.now(),
                null,
                payload);
    }

    // ---------- 展示层归零口径（与 AiOrgBudgetController.toResponse 一致）----------
    // 计数列由 reserve 懒重置；跨天/跨月但尚无新 run 时，当前窗口有效用量按 0 计。

    private static long effectiveDailyTokens(AiModelBudget budget) {
        return budget.needsDailyReset() ? 0L : orZero(budget.currentDailyTokens());
    }

    private static long effectiveDailyCents(AiModelBudget budget) {
        return budget.needsDailyReset() ? 0L : orZero(budget.currentDailyCents());
    }

    private static long effectiveMonthlyTokens(AiModelBudget budget) {
        return budget.needsMonthlyReset() ? 0L : orZero(budget.currentMonthlyTokens());
    }

    private static long effectiveMonthlyCents(AiModelBudget budget) {
        return budget.needsMonthlyReset() ? 0L : orZero(budget.currentMonthlyCents());
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
