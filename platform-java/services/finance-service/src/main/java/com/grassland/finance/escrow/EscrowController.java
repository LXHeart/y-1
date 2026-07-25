package com.grassland.finance.escrow;

import com.grassland.finance.account.Account;
import com.grassland.finance.account.AccountRepository;
import com.grassland.finance.event.EventEnvelope;
import com.grassland.finance.event.OutboxRepository;
import com.grassland.finance.security.FinanceCallerResolver;
import com.grassland.finance.security.FinanceException;
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

    public EscrowController(FinanceCallerResolver callers, AccountRepository accounts,
                            ReservationRepository reservations, OutboxRepository outbox) {
        this.callers = callers;
        this.accounts = accounts;
        this.reservations = reservations;
        this.outbox = outbox;
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
                .flatMap(caller -> reservations.findByEngagementRef(ref)
                        .<Reserved>map(r -> new Reserved(r, false))  // 幂等：既有 → 200
                        .switchIfEmpty(accounts.decrement(orgId, amount)
                                .switchIfEmpty(fail(409, "余额不足"))
                                .flatMap(acct -> reservations.create(acct.id(), orgId, ref, amount)
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
        return callers.resolveMerchantOrService(request, FinanceCallerResolver.MARKETPLACE_SERVICE)
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
        return callers.resolveMerchantOrService(request, FinanceCallerResolver.MARKETPLACE_SERVICE)
                .flatMap(caller -> reservations.findByEngagementRef(engagementRef)
                        .switchIfEmpty(fail(404, "预留不存在"))
                        .flatMap(r -> {
                            if (!r.organizationId().equals(caller.organizationId())) {
                                return fail(403, "无权操作该组织预留");
                            }
                            if (!"reserved".equals(r.status())) {
                                return fail(409, "该预留已处理");
                            }
                            return reservations.capture(r.id())  // 结算确认：reserved→captured，无余额变动（扣款在 reserve 时已发生）
                                    .switchIfEmpty(fail(409, "该预留已处理"));
                        })
                        .flatMap(r -> outbox.append(reservationEnvelope("FundsCaptured", r)).thenReturn(r))
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
