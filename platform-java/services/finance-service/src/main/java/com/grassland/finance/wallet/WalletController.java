package com.grassland.finance.wallet;

import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.finance.ledger.LedgerService;
import com.grassland.finance.payment.PaymentProviderAdapter;
import com.grassland.finance.provider.ProviderOperation;
import com.grassland.finance.provider.ProviderOperationRepository;
import com.grassland.finance.report.MonthParam;
import com.grassland.finance.security.FinanceCallerResolver;
import com.grassland.finance.security.FinanceException;
import com.grassland.reporting.ReportFormat;
import com.grassland.reporting.ReportRenderer;
import com.grassland.reporting.TabularReport;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 推荐官钱包 HTTP 入口——资金链的**收款侧出口**。
 *
 * <ul>
 *   <li>GET /api/finance/wallets/me — 我的余额 + 最近流水。</li>
 *   <li>GET /api/finance/wallets/me/statistics — 收入统计（按月汇总 + 按任务明细，任务书 #29+#30）。</li>
 *   <li>POST /api/finance/wallets/me/withdrawals — 提现（sandbox：立即出账，未接真实支付通道）。</li>
 * </ul>
 *
 * <p><b>只能操作自己的钱包</b>：accountId 一律取自 BFF 断言，不接受路径/请求体传入，故不存在越权维度。
 * <b>服务断言不可提现</b>（{@code callerKind=service}）——服务身份是给 Saga 用的，不该能把钱取走。
 */
@RestController
public class WalletController {

    /** 流水页大小：钱包卡片只做「最近若干条」，完整对账另开分页端点时再说。 */
    private static final int RECENT_ENTRIES = 20;

    /** 收入统计月份跨度上限（任务书 #29+#30 Stage 1）：防止拉全表。 */
    private static final int MAX_STATISTICS_MONTHS = 12;

    private final FinanceCallerResolver callers;
    private final WalletRepository wallets;
    private final WalletStatisticsRepository statistics;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final LedgerService ledger;
    private final PaymentProviderAdapter provider;
    private final ProviderOperationRepository providerOperations;

    public WalletController(FinanceCallerResolver callers, WalletRepository wallets,
                            WalletStatisticsRepository statistics, OutboxRepository outbox,
                            TransactionalOperator transactions, LedgerService ledger,
                            PaymentProviderAdapter provider, ProviderOperationRepository providerOperations) {
        this.callers = callers;
        this.wallets = wallets;
        this.statistics = statistics;
        this.outbox = outbox;
        this.transactions = transactions;
        this.ledger = ledger;
        this.provider = provider;
        this.providerOperations = providerOperations;
    }

    @GetMapping("/api/finance/wallets/me")
    public Mono<ResponseEntity<Map<String, Object>>> myWallet(ServerHttpRequest request) {
        return requireUser(request)
                .flatMap(accountId -> wallets.findByAccount(accountId)
                        // 没有钱包 = 一分钱没进过账，返回 0 而不是 404：对用户而言「余额 0」才是事实
                        .defaultIfEmpty(new Wallet(accountId, 0L, null))
                        .flatMap(wallet -> wallets.findEntries(accountId, RECENT_ENTRIES).collectList()
                                .map(entries -> ResponseEntity.ok(Map.of("success", true,
                                        "data", toBody(wallet, entries))))));
    }

    @GetMapping("/api/finance/wallets/me/export")
    public Mono<ResponseEntity<byte[]>> exportWallet(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "csv") String format,
            ServerHttpRequest request) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new FinanceException(400, "to 必须晚于 from");
        }
        ReportFormat reportFormat = ReportFormat.parse(format);
        return requireUser(request)
                .flatMap(accountId -> wallets.exportEntries(accountId, from, to, TabularReport.MAX_ROWS).collectList())
                .map(entries -> reportResponse("wallet-ledger", reportFormat, new TabularReport(
                        "Wallet Ledger",
                        List.of("entry_id", "entry_type", "amount_cents", "fee_cents",
                                "commission_bonus_cents", "engagement_ref", "memo", "created_at"),
                        entries.stream().<List<?>>map(entry -> List.of(
                                value(entry.id()), value(entry.entryType()), entry.amountCents(), entry.feeCents(),
                                entry.commissionBonusCents(), value(entry.engagementRef()), value(entry.memo()),
                                value(entry.createdAt()))).toList())));
    }

    /**
     * 推荐官收入统计（任务书 #29+#30 #29）：按月汇总 + 按任务（engagement）明细。
     *
     * <p>{@code from}/{@code to} 为含端月份（YYYY-MM），跨度上限 {@value #MAX_STATISTICS_MONTHS} 个月，
     * 超限 400。月切按北京时间（D2）。self-scoped：accountId 取自断言，orgId 不参与。
     * 空区间 → 全 0 月份数组，不 404。
     */
    @GetMapping("/api/finance/wallets/me/statistics")
    public Mono<ResponseEntity<Map<String, Object>>> myStatistics(
            @RequestParam String from, @RequestParam String to, ServerHttpRequest request) {
        YearMonth fromMonth = MonthParam.parse(from, "from");
        YearMonth toMonth = MonthParam.parse(to, "to");
        if (fromMonth.isAfter(toMonth)) {
            throw new FinanceException(400, "from 不得晚于 to");
        }
        long monthCount = fromMonth.until(toMonth, java.time.temporal.ChronoUnit.MONTHS) + 1;
        if (monthCount > MAX_STATISTICS_MONTHS) {
            throw new FinanceException(400, "月份跨度不得超过 " + MAX_STATISTICS_MONTHS + " 个月");
        }
        Instant start = MonthParam.range(fromMonth).start();
        Instant end = MonthParam.range(toMonth).end();
        return requireUser(request)
                .flatMap(accountId -> statistics.monthly(accountId, start, end).collectList()
                        .zipWith(statistics.byEngagement(accountId, start, end).collectList())
                        .map(tuple -> {
                            List<WalletStatisticsRepository.MonthlyIncome> months = tuple.getT1();
                            List<WalletStatisticsRepository.EngagementIncome> engagements = tuple.getT2();
                            Map<String, Object> map = new LinkedHashMap<>();
                            map.put("from", fromMonth.toString());
                            map.put("to", toMonth.toString());
                            map.put("months", fillMonths(fromMonth, toMonth, months)
                                    .stream().map(WalletController::monthBody).toList());
                            map.put("byEngagement", engagements.stream()
                                    .map(WalletController::engagementBody).toList());
                            return ResponseEntity.ok(Map.of("success", true, "data", map));
                        }));
    }

    /** 空月补全 0：SQL 只回有流水的月份，统计面板需要连续月份轴。 */
    private static List<WalletStatisticsRepository.MonthlyIncome> fillMonths(
            YearMonth from, YearMonth to, List<WalletStatisticsRepository.MonthlyIncome> found) {
        Map<String, WalletStatisticsRepository.MonthlyIncome> byMonth = new LinkedHashMap<>();
        found.forEach(m -> byMonth.put(m.month(), m));
        List<WalletStatisticsRepository.MonthlyIncome> out = new ArrayList<>();
        YearMonth cursor = from;
        while (!cursor.isAfter(to)) {
            out.add(byMonth.getOrDefault(cursor.toString(),
                    new WalletStatisticsRepository.MonthlyIncome(cursor.toString(), 0, 0, 0, 0, 0, 0, 0)));
            cursor = cursor.plusMonths(1);
        }
        return out;
    }

    private static Map<String, Object> monthBody(WalletStatisticsRepository.MonthlyIncome m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("month", m.month());
        map.put("taskPayoutCents", m.taskPayoutCents());
        map.put("commerceCommissionCents", m.commerceCommissionCents());
        map.put("withdrawalCents", m.withdrawalCents());
        map.put("clawbackCents", m.clawbackCents());
        map.put("grossCents", m.grossCents());
        map.put("feeCents", m.feeCents());
        map.put("netCents", m.netCents());
        return map;
    }

    private static Map<String, Object> engagementBody(WalletStatisticsRepository.EngagementIncome e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("engagementRef", e.engagementRef());
        map.put("payoutCents", e.payoutCents());
        map.put("feeCents", e.feeCents());
        map.put("count", e.entryCount());
        map.put("lastAt", e.lastAt() == null ? null : e.lastAt().toString());
        return map;
    }

    @PostMapping("/api/finance/wallets/me/withdrawals")
    public Mono<ResponseEntity<Map<String, Object>>> withdraw(@RequestBody WithdrawRequest body,
                                                              ServerHttpRequest request) {
        long amount = body.amountCents();
        String operationId = body.operationId() == null || body.operationId().isBlank()
                ? "withdraw:" + UUID.randomUUID()
                : body.operationId().trim();
        String providerRef = provider.channel() + ":payout:" + operationId;
        return requireUser(request)
                .flatMap(accountId -> transactions.transactional(
                        providerOperations.registerIfAbsent(
                                        provider.channel(), operationId, "payout", accountId,
                                        amount, "CNY", providerRef)
                                .flatMap(operation -> completeWithdrawal(
                                        accountId, amount, operationId, providerRef))
                                .switchIfEmpty(providerOperations.findByOperationId(operationId)
                                        .flatMap(existing -> requirePayoutMatch(
                                                existing, accountId, amount, providerRef))
                                        .then(wallets.findByAccount(accountId))))
                        .flatMap(wallet -> wallets.findEntries(accountId, RECENT_ENTRIES).collectList()
                                .map(entries -> ResponseEntity.ok(Map.of("success", true,
                                        "data", toBody(wallet, entries))))));
    }

    private Mono<Wallet> completeWithdrawal(
            String accountId, long amount, String operationId, String providerRef) {
        return wallets.debit(accountId, amount)
                .switchIfEmpty(Mono.error(new FinanceException(409, "余额不足")))
                .flatMap(wallet -> wallets.appendEntry(
                                accountId, WalletEntryType.WITHDRAWAL, -amount, 0,
                                operationId, "sandbox 提现")
                        .then(ledger.postWithdraw(accountId, amount))
                        .then(outbox.append(new EventEnvelope(
                                UUID.randomUUID().toString(), "WithdrawalCompleted", "Wallet",
                                accountId, 1, Instant.now(), operationId,
                                Map.of("accountId", accountId, "amountCents", amount,
                                        "operationId", operationId, "providerRef", providerRef))))
                        .thenReturn(wallet));
    }

    private Mono<ProviderOperation> requirePayoutMatch(
            ProviderOperation existing, String accountId, long amount, String providerRef) {
        return ProviderOperationRepository.matches(
                        existing, provider.channel(), "payout", accountId, amount, "CNY", providerRef)
                ? Mono.just(existing)
                : Mono.error(new FinanceException(409, "提现幂等参数冲突"));
    }

    /** 钱包是账号级私有资源：必须是终端用户断言（服务断言不得动钱包）。 */
    private Mono<String> requireUser(ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> caller.isService()
                        ? Mono.error(new FinanceException(403, "服务身份不可操作钱包"))
                        : Mono.just(caller.accountId()));
    }

    private static Map<String, Object> toBody(Wallet wallet, List<WalletEntry> entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("accountId", wallet.accountId());
        map.put("balanceCents", wallet.balanceCents());
        map.put("updatedAt", wallet.updatedAt() == null ? null : wallet.updatedAt().toString());
        map.put("entries", entries.stream().map(WalletController::entryBody).toList());
        return map;
    }

    private static Map<String, Object> entryBody(WalletEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entry.id());
        map.put("entryType", entry.entryType());
        map.put("amountCents", entry.amountCents());
        map.put("feeCents", entry.feeCents());
        map.put("commissionBonusCents", entry.commissionBonusCents());
        map.put("engagementRef", entry.engagementRef());
        map.put("memo", entry.memo());
        map.put("createdAt", entry.createdAt() == null ? null : entry.createdAt().toString());
        return map;
    }

    private static Object value(Object value) {
        return value == null ? "" : value;
    }

    private static ResponseEntity<byte[]> reportResponse(
            String filename, ReportFormat format, TabularReport report) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(format.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename + "." + format.extension()).build().toString())
                .body(ReportRenderer.render(report, format));
    }
}
