package com.grassland.intelligence.ai.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.ai.run.AiBudgetAlertRepository.AiBudgetAlert;
import com.grassland.intelligence.ai.run.AiBudgetAlertRepository.Level;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@link BudgetAlertScanner} 阈值数学与防骚扰矩阵：80% 警告 / 严格大于才 exceeded /
 * 上限未配置跳过 / 同等级不重发 / 只升不降 / 确定性 eventId / 跨天展示层归零。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BudgetAlertScanner (阈值与去重)")
class BudgetAlertScannerTest {

    private static final String ORG = "org-alert-1";
    private static final String TODAY = LocalDate.now().toString();

    @Mock
    AiModelBudgetRepository budgets;
    @Mock
    AiBudgetAlertRepository alerts;
    @Mock
    OutboxRepository outbox;
    @Mock
    TransactionalOperator transactions;

    private BudgetAlertScanner scanner;
    private final AtomicReference<EventEnvelope> appended = new AtomicReference<>();

    @BeforeEach
    void wire() {
        scanner = new BudgetAlertScanner(budgets, alerts, outbox, transactions, 80);
        lenient().when(transactions.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(outbox.append(any(EventEnvelope.class))).thenAnswer(invocation -> {
            appended.set(invocation.getArgument(0));
            return Mono.empty();
        });
    }

    private AiModelBudget budget(Long dailyTokenLimit, long dailyTokens) {
        return new AiModelBudget(UUID.randomUUID(), ORG, "*", "*",
                null, dailyTokenLimit, null, null, null, null,
                dailyTokens, 0L, 0L, 0L, LocalDate.now(),
                1L, true, null, null);
    }

    private void scan(AiModelBudget budget) {
        when(budgets.listOrganizationBudgetRows()).thenReturn(Flux.just(budget));
        scanner.scanOnce().then().block();
    }

    private UUID expectedEventId(String rule, String period, Level level) {
        return UUID.nameUUIDFromBytes(
                ("AiBudgetAlert:" + ORG + ":" + rule + ":" + period + ":" + level.dbValue())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("用量达 80% 触发 warning；eventId 按 组织+规则+窗口+等级 确定性生成")
    void warningEmittedOnceWithDeterministicId() {
        when(alerts.find(ORG, "daily_tokens", TODAY)).thenReturn(Mono.empty());
        when(alerts.upsert(ORG, "daily_tokens", TODAY, Level.WARNING, 80L, 100L))
                .thenReturn(Mono.empty());

        scan(budget(100L, 80L));

        assertThat(appended.get()).isNotNull();
        assertThat(appended.get().eventType()).isEqualTo("AiOrgBudgetThresholdCrossed");
        assertThat(appended.get().eventId()).isEqualTo(expectedEventId("daily_tokens", TODAY, Level.WARNING).toString());
        assertThat(appended.get().payload()).containsEntry("usage", 80L).containsEntry("limit", 100L)
                .containsEntry("level", "warning").containsEntry("window", "daily");
    }

    @Test
    @DisplayName("79% 未达阈值不发；上限未配置（null）整维跳过")
    void belowThresholdAndUnlimitedSkipped() {
        when(budgets.listOrganizationBudgetRows())
                .thenReturn(Flux.just(budget(100L, 79L), budget(null, 10_000L)));
        scanner.scanOnce().then().block();
        assertThat(appended.get()).isNull();
    }

    @Test
    @DisplayName("严格大于上限才 exceeded（等于上限是 warning）")
    void exceededRequiresStrictlyGreaterThan() {
        when(alerts.find(ORG, "daily_tokens", TODAY)).thenReturn(Mono.empty());
        when(alerts.upsert(any(), any(), any(), any(), anyLong(), anyLong())).thenReturn(Mono.empty());

        scan(budget(100L, 101L));

        assertThat(appended.get().payload()).containsEntry("level", "exceeded");
        assertThat(appended.get().eventId())
                .isEqualTo(expectedEventId("daily_tokens", TODAY, Level.EXCEEDED).toString());
    }

    @Test
    @DisplayName("同窗同等级不重发（幂等扫描）；升级到 exceeded 才再发")
    void sameLevelSuppressedAndUpgradeEmitted() {
        AiBudgetAlert existing = new AiBudgetAlert(
                UUID.randomUUID(), ORG, "daily_tokens", TODAY, Level.WARNING, 85L, 100L);
        when(alerts.find(ORG, "daily_tokens", TODAY)).thenReturn(Mono.just(existing));

        scan(budget(100L, 90L));  // 仍是 warning 区间
        assertThat(appended.get()).isNull();

        when(alerts.upsert(ORG, "daily_tokens", TODAY, Level.EXCEEDED, 105L, 100L))
                .thenReturn(Mono.empty());
        scan(budget(100L, 105L));  // 跃迁 exceeded
        assertThat(appended.get()).isNotNull();
        assertThat(appended.get().payload()).containsEntry("level", "exceeded");
    }

    @Test
    @DisplayName("等级只升不降：已 exceeded 后回落 warning 区间不重发不翻状态")
    void downgradeIgnored() {
        AiBudgetAlert existing = new AiBudgetAlert(
                UUID.randomUUID(), ORG, "daily_tokens", TODAY, Level.EXCEEDED, 120L, 100L);
        when(alerts.find(ORG, "daily_tokens", TODAY)).thenReturn(Mono.just(existing));

        scan(budget(100L, 85L));
        assertThat(appended.get()).isNull();
    }

    @Test
    @DisplayName("跨天未发新 run：日计数展示层归零，不告警（与预算面板口径一致）")
    void staleDailyCounterTreatedAsZero() {
        AiModelBudget stale = new AiModelBudget(UUID.randomUUID(), ORG, "*", "*",
                null, 100L, null, null, null, null,
                95L, 0L, 0L, 0L, LocalDate.now().minusDays(1),
                1L, true, null, null);
        when(budgets.listOrganizationBudgetRows()).thenReturn(Flux.just(stale));

        scanner.scanOnce().then().block();
        assertThat(appended.get()).isNull();
    }
}
