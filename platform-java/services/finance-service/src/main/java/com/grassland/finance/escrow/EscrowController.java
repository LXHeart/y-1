package com.grassland.finance.escrow;

import com.grassland.finance.account.Account;
import com.grassland.finance.account.AccountRepository;
import com.grassland.finance.event.EventEnvelope;
import com.grassland.finance.event.OutboxRepository;
import com.grassland.finance.ledger.LedgerService;
import com.grassland.finance.security.FinanceCallerResolver;
import com.grassland.finance.security.FinanceException;
import com.grassland.finance.wallet.PlatformFeePolicy;
import com.grassland.finance.wallet.WalletEntryType;
import com.grassland.finance.wallet.WalletRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.reactive.TransactionalOperator;
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
    private final EscrowLifecycleService lifecycle;
    private final TransactionalOperator transactions;
    private final LedgerService ledger;

    public EscrowController(FinanceCallerResolver callers, AccountRepository accounts,
                            ReservationRepository reservations, OutboxRepository outbox,
                            WalletRepository wallets, PlatformFeePolicy fees,
                            EscrowLifecycleService lifecycle, TransactionalOperator transactions,
                            LedgerService ledger) {
        this.callers = callers;
        this.accounts = accounts;
        this.reservations = reservations;
        this.outbox = outbox;
        this.wallets = wallets;
        this.fees = fees;
        this.lifecycle = lifecycle;
        this.transactions = transactions;
        this.ledger = ledger;
    }

    @PostMapping("/api/finance/accounts/{orgId}/credit")
    public Mono<ResponseEntity<Map<String, Object>>> credit(@PathVariable String orgId,
                                                            @RequestBody CreditRequest body, ServerHttpRequest request) {
        long amount = body.amountCents();
        return callers.requireMerchant(request)
                .filter(caller -> orgId.equals(caller.organizationId()))
                .switchIfEmpty(fail(403, "无权操作该组织账户"))
                .flatMap(caller -> transactions.transactional(
                        accounts.credit(orgId, amount)
                                .switchIfEmpty(fail(404, "账户不存在，请先开户"))
                                .flatMap(acct -> ledger.postDeposit(orgId, amount)
                                        .then(outbox
                                                .append(accountEnvelope("AccountCredited", acct, amount, caller.accountId()))
                                                .thenReturn(acct))))
                        .map(acct -> ResponseEntity.ok(Map.of("success", true, "data", toBody(acct)))));
    }

    @PostMapping("/api/finance/accounts/{orgId}/reservations")
    public Mono<ResponseEntity<Map<String, Object>>> reserve(@PathVariable String orgId,
                                                             @RequestBody ReserveRequest body, ServerHttpRequest request) {
        String ref = body.engagementRef();
        long amount = body.amountCents();
        String payeeAccountId = normalizePayeeAccountId(body.payeeAccountId());
        int commissionBonusBps = body.commissionBonusBps();
        return callers.authorizeForOrg(request, orgId, FinanceCallerResolver.MARKETPLACE_SERVICE)
                // D-05 单笔交易上限：仅对终端商家用户断言执行；服务断言（Saga，tier=null）豁免——
                // 其金额已在 marketplace 发布任务时按同值 maxTxAmountCents 校验，此处按 null→0 会拦死 4F Saga。
                .filter(caller -> caller.isService()
                        || FinanceTxQuotaPolicy.isWithinLimit(caller.permissionTier(), amount))
                .switchIfEmpty(fail(409, "交易金额超出本组织单笔上限"))
                .flatMap(caller -> {
                    if ((payeeAccountId != null || commissionBonusBps > 0)
                            && !caller.isServicePrincipal(FinanceCallerResolver.MARKETPLACE_SERVICE)) {
                        return fail(403, "仅 marketplace 服务可指定收款人或授予平台佣金补贴");
                    }
                    return transactions.transactional(reserveWork(
                            orgId, ref, amount, payeeAccountId, commissionBonusBps));
                })
                .map(res -> ResponseEntity.status(res.created() ? HttpStatus.CREATED : HttpStatus.OK)
                        .body(Map.of("success", true, "data", toBody(res.reservation()))));
    }

    /** 预留领域写（扣余额 + 建预留）+ outbox append，绑进同一事务（Slice 7C）。幂等：engagementRef 既有→200；并发冲突→既有。 */
    private Mono<Reserved> reserveWork(String orgId, String ref, long amount, String payeeAccountId,
                                       int commissionBonusBps) {
        long commissionBonusCents = CommissionBonusPolicy.calculateCents(amount, commissionBonusBps);
        CommissionBonusPolicy.validateTotalPayout(amount, commissionBonusCents);
        return reservations.findByEngagementRef(ref)
                .flatMap(r -> existingReservation(
                        orgId, ref, amount, payeeAccountId, commissionBonusBps, commissionBonusCents, r))
                .switchIfEmpty(accounts.decrement(orgId, amount)
                        .switchIfEmpty(fail(409, "余额不足"))
                        .flatMap(acct -> reservations.create(acct.id(), orgId, ref, amount, payeeAccountId,
                                        commissionBonusBps, commissionBonusCents)
                                .<Reserved>map(r -> new Reserved(r, true))
                                // 并发唯一键落败：撤销本事务内刚做的余额扣减，再读取胜者。
                                .switchIfEmpty(accounts.credit(orgId, amount)
                                        .then(reservations.findByEngagementRef(ref))
                                        .flatMap(r -> existingReservation(
                                                orgId, ref, amount, payeeAccountId,
                                                commissionBonusBps, commissionBonusCents, r))
                                        .switchIfEmpty(fail(409, "幂等预留冲突")))))
                .flatMap(res -> (res.created()
                        ? ledger.postReserve(orgId, ref, amount)
                                .then(outbox.append(reservationEnvelope("FundsReserved", res.reservation())).thenReturn(res))
                        : Mono.just(res)));
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
                            return lifecycle.release(r);
                        })
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
                            return lifecycle.capture(r);
                        })
                        .map(r -> ResponseEntity.ok(Map.of("success", true, "data", toBody(r)))));
    }

    /** 冲正（D-06 争议处置，Slice 6C Phase D）：captured → refunded + 还原余额（判商家方胜诉、资金已 capture 时退还商家）。
     *  仅 trust 服务可调（争议终局钱侧分派）。镜像 release，守卫态为 captured。 */
    @PostMapping("/api/finance/reservations/{engagementRef}/reverse")
    public Mono<ResponseEntity<Map<String, Object>>> reverse(@PathVariable String engagementRef, ServerHttpRequest request) {
        return callers.requireService(request, FinanceCallerResolver.TRUST_SERVICE)
                .flatMap(caller -> reservations.findByEngagementRef(engagementRef)
                        .switchIfEmpty(fail(404, "预留不存在"))
                        .flatMap(r -> {
                            if (!r.organizationId().equals(caller.organizationId())) {
                                return fail(403, "无权操作该组织预留");
                            }
                            if (!"captured".equals(r.status())) {
                                return fail(409, "该预留不可冲正（须 captured）");
                            }
                            return lifecycle.reverse(r);
                        })
                        .map(r -> ResponseEntity.ok(Map.of("success", true, "data", toBody(r)))));
    }

    @PostMapping("/api/finance/reservations/{engagementRef}/reconcile")
    public Mono<ResponseEntity<Map<String, Object>>> reconcile(
            @PathVariable String engagementRef,
            @RequestBody ReconciliationRequest body,
            ServerHttpRequest request) {
        return callers.requireServiceForOrg(
                        request, body.organizationId(), FinanceCallerResolver.MARKETPLACE_SERVICE)
                .flatMap(caller -> lifecycle.reconcile(
                        body.organizationId(), engagementRef, body.finalDecision()))
                .map(result -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("outcome", result.outcome());
                    data.put("reason", result.reason());
                    if (result.reservation() != null) {
                        data.put("reservation", toBody(result.reservation()));
                    }
                    return ResponseEntity.ok(Map.of("success", true, "data", data));
                });
    }

    /** {@code payeeAccountId} 为收款推荐官的用户账号（非 finance ledger account），
     *  供 identity 通知中心解析收件人（Slice 12 Stage 3）。 */
    private EventEnvelope reservationEnvelope(String eventType, FundsReservation r) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reservationId", r.id());
        payload.put("accountId", r.accountId());
        payload.put("organizationId", r.organizationId());
        payload.put("engagementRef", r.engagementRef());
        payload.put("amountCents", r.amountCents());
        payload.put("status", r.status());
        payload.put("commissionBonusBps", r.commissionBonusBps());
        payload.put("commissionBonusCents", r.commissionBonusCents());
        if (r.payeeAccountId() != null) {
            payload.put("payeeAccountId", r.payeeAccountId());
        }
        return new EventEnvelope(UUID.randomUUID().toString(), eventType, "FundsReservation",
                r.id(), 1, Instant.now(), null, payload);
    }

    /** {@code accountId} 是 finance 组织 ledger account；{@code payeeAccountId} 才是用户账号
     *  （充值场景即发起充值的商家本人），供 identity 通知中心解析收件人（Slice 12 Stage 3）。 */
    private EventEnvelope accountEnvelope(String eventType, Account acct, long amountCents, String payeeAccountId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", acct.id());
        payload.put("organizationId", acct.organizationId());
        payload.put("amountCents", amountCents);
        payload.put("balanceCents", acct.balanceCents());
        if (payeeAccountId != null) {
            payload.put("payeeAccountId", payeeAccountId);
        }
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
        m.put("commissionBonusBps", r.commissionBonusBps());
        m.put("commissionBonusCents", r.commissionBonusCents());
        if (r.payoutCents() == null) {
            m.put("basePayoutCents", null);
            m.put("platformFeeCents", null);
        } else {
            long basePayout = r.payoutCents() - r.commissionBonusCents();
            m.put("basePayoutCents", basePayout);
            m.put("platformFeeCents", r.amountCents() - basePayout);
        }
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

    private static Mono<Reserved> existingReservation(
            String orgId,
            String engagementRef,
            long amountCents,
            String payeeAccountId,
            int commissionBonusBps,
            long commissionBonusCents,
            FundsReservation reservation) {
        boolean sameScope = orgId.equals(reservation.organizationId())
                && engagementRef.equals(reservation.engagementRef())
                && amountCents == reservation.amountCents()
                && Objects.equals(payeeAccountId, reservation.payeeAccountId())
                && commissionBonusBps == reservation.commissionBonusBps()
                && commissionBonusCents == reservation.commissionBonusCents();
        return sameScope
                ? Mono.just(new Reserved(reservation, false))
                : fail(422, "engagementRef 预留范围冲突");
    }

    private static String normalizePayeeAccountId(String payeeAccountId) {
        return payeeAccountId == null || payeeAccountId.isBlank() ? null : payeeAccountId;
    }

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new FinanceException(status, message));
    }
}
