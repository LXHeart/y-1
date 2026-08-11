package com.grassland.finance.wallet;

import com.grassland.finance.event.EventEnvelope;
import com.grassland.finance.event.OutboxRepository;
import com.grassland.finance.ledger.LedgerService;
import com.grassland.finance.payment.PaymentProviderAdapter;
import com.grassland.finance.provider.ProviderOperation;
import com.grassland.finance.provider.ProviderOperationRepository;
import com.grassland.finance.security.FinanceCallerResolver;
import com.grassland.finance.security.FinanceException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 推荐官钱包 HTTP 入口——资金链的**收款侧出口**。
 *
 * <ul>
 *   <li>GET /api/finance/wallets/me — 我的余额 + 最近流水。</li>
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

    private final FinanceCallerResolver callers;
    private final WalletRepository wallets;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final LedgerService ledger;
    private final PaymentProviderAdapter provider;
    private final ProviderOperationRepository providerOperations;

    public WalletController(FinanceCallerResolver callers, WalletRepository wallets, OutboxRepository outbox,
                            TransactionalOperator transactions, LedgerService ledger,
                            PaymentProviderAdapter provider, ProviderOperationRepository providerOperations) {
        this.callers = callers;
        this.wallets = wallets;
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
}
