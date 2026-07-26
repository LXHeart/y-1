package com.grassland.finance.escrow;

import com.grassland.finance.account.Account;
import com.grassland.finance.account.AccountRepository;
import com.grassland.finance.event.EventEnvelope;
import com.grassland.finance.event.OutboxRepository;
import com.grassland.finance.security.FinanceCallerResolver;
import com.grassland.finance.security.FinanceException;
import com.grassland.finance.wallet.PlatformFeePolicy;
import com.grassland.finance.wallet.WalletEntryType;
import com.grassland.finance.wallet.WalletRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * escrow HTTP 入口（草场 Epic 4 Slice 4E / HLD 5.4 escrow「预留/释放」）。
 *
 * <ul>
 *   <li>POST /api/finance/accounts/{orgId}/credit — sandbox 充值（requireMerchant + org 自查；原子加余额）。</li>
 *   <li>POST /api/finance/accounts/{orgId}/reservations — 预留（原子条件扣余额；余额不足 409；engagementRef 幂等）。</li>
 *   <li>POST /api/finance/reservations/{engagementRef}/release — 释放（reserved→released + 还原余额）。</li>
 * </ul>
 *
 * <p>身份靠 {@link FinanceCallerResolver}（BFF 断言 + 服务断言）；org 归属用 caller.organizationId 自查（HLD 7.4）。
 * 预留幂等按 engagement_ref（Saga 重试安全）。reserve/release 同时接受终端商家用户断言与 marketplace 服务断言
 * （HLD 11.1 服务身份，Slice 4F Saga 跨服务调用）；credit 仅商家用户（sandbox 人工充值）。
 * 错误统一由全局 {@code FinanceErrorHandler} 处理。
 */
@RestController
public class EscrowController {

    private final FinanceCallerResolver callers;
    private final AccountRepository accounts;
    private final ReservationRepository reservations;
    private final OutboxRepository outbox;
    private final WalletRepository wallets;
    private final PlatformFeePolicy fees;

    public EscrowController(FinanceCallerResolver callers, AccountRepository accounts,
                            ReservationRepository reservations, OutboxRepository outbox,
                            WalletRepository wallets, PlatformFeePolicy fees) {
        this.callers = callers;
        this.accounts = accounts;
        this.reservations = reservations;
        this.outbox = outbox;
        this.wallets = wallets;
        this.fees = fees;
    }

    /**
     * 分账：把 capture 下来的净额打进推荐官钱包 + 记流水 + 发 {@code SplitCompleted}。
     *
     * <p>无收款人（存量预留 / 非撮合场景）→ 原样返回，不动任何余额，与本次改动前行为一致。
     */
    private Mono<FundsReservation> splitToPayee(FundsReservation captured) {
        if (captured.payeeAccountId() == null || captured.payoutCents() == null || captured.payoutCents() <= 0) {
            return Mono.just(captured);
        }
        long payout = captured.payoutCents();
        long fee = captured.amountCents() - payout;
        return wallets.credit(captured.payeeAccountId(), payout)
                .then(wallets.appendEntry(captured.payeeAccountId(), WalletEntryType.TASK_PAYOUT,
                        payout, fee, captured.engagementRef(), "任务结算入账"))
                .then(outbox.append(new EventEnvelope(
                        UUID.randomUUID().toString(), "SplitCompleted", "FundsReservation",
                        captured.id(), 1, Instant.now(), null,
                        Map.of("engagementRef", String.valueOf(captured.engagementRef()),
                                "payeeAccountId", captured.payeeAccountId(),
                                "grossCents", captured.amountCents(),
                                "payoutCents", payout,
                                "platformFeeCents", fee))))
                .thenReturn(captured);
    }

    /**
     * 冲正前从推荐官钱包扣回已分账的净额（D-06：已 capture 后判商家胜诉）。
     *
     * <p>扣的是 {@code payoutCents} 而非 {@code amountCents}——推荐官本来就没拿到平台抽成那部分，
     * 按毛额扣会多扣他一笔。钱包余额不足（多半是已提现）→ **409 中止整个冲正**，宁可挂起等人工处理，
     * 也不能一边给商家退款一边让推荐官账上凭空少钱或变负。
     */
    private Mono<Void> clawbackFromPayee(FundsReservation reservation) {
        if (reservation.payeeAccountId() == null || reservation.payoutCents() == null
                || reservation.payoutCents() <= 0) {
            return Mono.empty();
        }
        long payout = reservation.payoutCents();
        return wallets.debit(reservation.payeeAccountId(), payout)
                .switchIfEmpty(Mono.error(new FinanceException(409,
                        "推荐官余额不足以冲正（可能已提现），需人工处理")))
                .flatMap(wallet -> wallets.appendEntry(reservation.payeeAccountId(), WalletEntryType.CLAWBACK,
                        -payout, 0, reservation.engagementRef(), "争议冲正扣回"))
                .then();
    }

    @PostMapping("/api/finance/accounts/{orgId}/credit")
    public Mono<ResponseEntity<Map<String, Object>>> credit(@PathVariable String orgId,
                                                            @RequestBody CreditRequest body, ServerHttpRequest request) {
        long amount = body.amountCents();
        return callers.requireMerchant(request)
                .filter(caller -> orgId.equals(caller.organizationId()))
                .switchIfEmpty(fail(403, "无权操作该组织账户"))
                .flatMap(caller -> accounts.credit(orgId, amount)
                        .switchIfEmpty(fail(404, "账户不存在，请先开户"))
                        .flatMap(acct -> outbox.append(accountEnvelope("AccountCredited", acct, amount)).thenReturn(acct))
                        .map(acct -> ResponseEntity.ok(Map.of("success", true, "data", toBody(acct)))));
    }

    @PostMapping("/api/finance/accounts/{orgId}/reservations")
    public Mono<ResponseEntity<Map<String, Object>>> reserve(@PathVariable String orgId,
                                                             @RequestBody ReserveRequest body, ServerHttpRequest request) {
        String ref = body.engagementRef();
        long amount = body.amountCents();
        return callers.authorizeForOrg(request, orgId, FinanceCallerResolver.MARKETPLACE_SERVICE)
                // D-05 单笔交易上限：仅对终端商家用户断言执行；服务断言（Saga，tier=null）豁免——
                // 其金额已在 marketplace 发布任务时按同值 maxTxAmountCents 校验，此处按 null→0 会拦死 4F Saga。
                .filter(caller -> caller.isService()
                        || FinanceTxQuotaPolicy.isWithinLimit(caller.permissionTier(), amount))
                .switchIfEmpty(fail(409, "交易金额超出本组织单笔上限"))
                .flatMap(caller -> reservations.findByEngagementRef(ref)
                        .<Reserved>map(r -> new Reserved(r, false))  // 幂等：既有 → 200
                        .switchIfEmpty(accounts.decrement(orgId, amount)
                                .switchIfEmpty(fail(409, "余额不足"))
                                .flatMap(acct -> reservations.create(acct.id(), orgId, ref, amount, body.payeeAccountId())
                                        .<Reserved>map(r -> new Reserved(r, true))
                                        .switchIfEmpty(reservations.findByEngagementRef(ref)
                                                .<Reserved>map(r -> new Reserved(r, false))))))  // 并发冲突 → 既有
                .flatMap(res -> (res.created()
                        ? outbox.append(reservationEnvelope("FundsReserved", res.reservation())).thenReturn(res.reservation())
                        : Mono.just(res.reservation()))
                        .map(r -> ResponseEntity.status(res.created() ? HttpStatus.CREATED : HttpStatus.OK)
                                .body(Map.of("success", true, "data", toBody(r)))));
    }

    @PostMapping("/api/finance/reservations/{engagementRef}/release")
    public Mono<ResponseEntity<Map<String, Object>>> release(@PathVariable String engagementRef, ServerHttpRequest request) {
        return callers.resolveMerchantOrServices(request, FinanceCallerResolver.MARKETPLACE_SERVICE, FinanceCallerResolver.TRUST_SERVICE)
                .flatMap(caller -> reservations.findByEngagementRef(engagementRef)
                        .switchIfEmpty(fail(404, "预留不存在"))
                        .flatMap(r -> {
                            if (!r.organizationId().equals(caller.organizationId())) {
                                return fail(403, "无权操作该组织预留");
                            }
                            if (!"reserved".equals(r.status())) {
                                return fail(409, "该预留已处理");
                            }
                            return reservations.release(r.id())
                                    .switchIfEmpty(fail(409, "该预留已处理"))
                                    .flatMap(released -> accounts.credit(r.organizationId(), r.amountCents())
                                            .thenReturn(released));
                        })
                        .flatMap(r -> outbox.append(reservationEnvelope("FundsReleased", r)).thenReturn(r))
                        .map(r -> ResponseEntity.ok(Map.of("success", true, "data", toBody(r)))));
    }

    @PostMapping("/api/finance/reservations/{engagementRef}/capture")
    public Mono<ResponseEntity<Map<String, Object>>> capture(@PathVariable String engagementRef, ServerHttpRequest request) {
        return callers.resolveMerchantOrServices(request, FinanceCallerResolver.MARKETPLACE_SERVICE, FinanceCallerResolver.TRUST_SERVICE)
                .flatMap(caller -> reservations.findByEngagementRef(engagementRef)
                        .switchIfEmpty(fail(404, "预留不存在"))
                        .flatMap(r -> {
                            if (!r.organizationId().equals(caller.organizationId())) {
                                return fail(403, "无权操作该组织预留");
                            }
                            if (!"reserved".equals(r.status())) {
                                return fail(409, "该预留已处理");
                            }
                            // 商家余额在 reserve 时已扣；这里把钱**分给推荐官**（无收款人则维持旧行为，钱留平台账）
                            Long payout = r.payeeAccountId() == null ? null : fees.payoutFor(r.amountCents());
                            return reservations.capture(r.id(), payout)
                                    .switchIfEmpty(fail(409, "该预留已处理"))
                                    .flatMap(this::splitToPayee);
                        })
                        .flatMap(r -> outbox.append(reservationEnvelope("FundsCaptured", r)).thenReturn(r))
                        .map(r -> ResponseEntity.ok(Map.of("success", true, "data", toBody(r)))));
    }

    /** 冲正（D-06 争议处置，Slice 6C Phase D）：captured → refunded + 还原余额（判商家方胜诉、资金已 capture 时退还商家）。
     *  仅 trust 服务可调（争议终局钱侧分派）。镜像 release，守卫态为 captured。 */
    @PostMapping("/api/finance/reservations/{engagementRef}/reverse")
    public Mono<ResponseEntity<Map<String, Object>>> reverse(@PathVariable String engagementRef, ServerHttpRequest request) {
        return callers.resolveMerchantOrService(request, FinanceCallerResolver.TRUST_SERVICE)
                .flatMap(caller -> reservations.findByEngagementRef(engagementRef)
                        .switchIfEmpty(fail(404, "预留不存在"))
                        .flatMap(r -> {
                            if (!r.organizationId().equals(caller.organizationId())) {
                                return fail(403, "无权操作该组织预留");
                            }
                            if (!"captured".equals(r.status())) {
                                return fail(409, "该预留不可冲正（须 captured）");
                            }
                            // 先从推荐官钱包扣回已分账的净额，再退商家——顺序反了就等于凭空造钱
                            return clawbackFromPayee(r)
                                    .then(reservations.reverse(r.id()))  // captured→refunded
                                    .switchIfEmpty(fail(409, "该预留不可冲正（须 captured）"))
                                    .flatMap(refunded -> accounts.credit(r.organizationId(), r.amountCents())  // 余额还原
                                            .thenReturn(refunded));
                        })
                        .flatMap(r -> outbox.append(reservationEnvelope("FundsReversed", r)).thenReturn(r))
                        .map(r -> ResponseEntity.ok(Map.of("success", true, "data", toBody(r)))));
    }

    private EventEnvelope reservationEnvelope(String eventType, FundsReservation r) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reservationId", r.id());
        payload.put("accountId", r.accountId());
        payload.put("organizationId", r.organizationId());
        payload.put("engagementRef", r.engagementRef());
        payload.put("amountCents", r.amountCents());
        payload.put("status", r.status());
        return new EventEnvelope(UUID.randomUUID().toString(), eventType, "FundsReservation",
                r.id(), 1, Instant.now(), null, payload);
    }

    private EventEnvelope accountEnvelope(String eventType, Account acct, long amountCents) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", acct.id());
        payload.put("organizationId", acct.organizationId());
        payload.put("amountCents", amountCents);
        payload.put("balanceCents", acct.balanceCents());
        return new EventEnvelope(UUID.randomUUID().toString(), eventType, "Account",
                acct.id(), 1, Instant.now(), null, payload);
    }

    private Map<String, Object> toBody(FundsReservation r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("accountId", r.accountId());
        m.put("organizationId", r.organizationId());
        m.put("engagementRef", r.engagementRef());
        m.put("amountCents", r.amountCents());
        m.put("status", r.status());
        m.put("payeeAccountId", r.payeeAccountId());
        m.put("payoutCents", r.payoutCents());   // capture 后 = 实际打给推荐官的净额；null = 未分账
        m.put("createdAt", r.createdAt() == null ? null : r.createdAt().toString());
        return m;
    }

    private Map<String, Object> toBody(Account acct) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", acct.id());
        m.put("organizationId", acct.organizationId());
        m.put("balanceCents", acct.balanceCents());
        m.put("currency", acct.currency());
        return m;
    }

    private record Reserved(FundsReservation reservation, boolean created) {}

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new FinanceException(status, message));
    }
}
