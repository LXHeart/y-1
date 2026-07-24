package com.grassland.finance.account;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 账户 HTTP 入口（草场 Epic 4 Slice 4D / HLD 5.4 ledger）。
 *
 * <ul>
 *   <li>POST /api/finance/accounts — 开户（requireMerchant；org 用 caller.organizationId，须非空；
 *       幂等：已存在返回既有；首次创建写 outbox {@code AccountProvisioned}；201 首次 / 200 既有）。</li>
 *   <li>GET /api/finance/accounts/{orgId} — 查余额（requireMerchant + caller.organizationId==orgId 资源自查；
 *       不存在 404）。</li>
 * </ul>
 *
 * <p>身份靠 {@link FinanceCallerResolver}（BFF 断言）；org 归属用 caller.organizationId 自查（HLD 7.4）。
 * 本 slice 无充值路径，余额恒 0；错误统一由全局 {@code FinanceErrorHandler} 处理。
 */
@RestController
public class AccountController {

    private final FinanceCallerResolver callers;
    private final AccountRepository accounts;
    private final OutboxRepository outbox;

    public AccountController(FinanceCallerResolver callers, AccountRepository accounts, OutboxRepository outbox) {
        this.callers = callers;
        this.accounts = accounts;
        this.outbox = outbox;
    }

    @PostMapping("/api/finance/accounts")
    public Mono<ResponseEntity<Map<String, Object>>> provision(ServerHttpRequest request) {
        return callers.requireMerchant(request)
                .filter(caller -> caller.organizationId() != null)
                .switchIfEmpty(fail(403, "无组织归属，无法开户"))
                .flatMap(caller -> provision(caller.organizationId()))
                .flatMap(p -> (p.created()
                        ? outbox.append(envelope("AccountProvisioned", p.account())).thenReturn(p.account())
                        : Mono.just(p.account()))
                        .map(a -> ResponseEntity.status(p.created() ? HttpStatus.CREATED : HttpStatus.OK)
                                .body(Map.of("success", true, "data", toBody(a)))));
    }

    @GetMapping("/api/finance/accounts/{orgId}")
    public Mono<ResponseEntity<Map<String, Object>>> balance(@PathVariable String orgId, ServerHttpRequest request) {
        return callers.requireMerchant(request)
                .flatMap(caller -> {
                    if (!orgId.equals(caller.organizationId())) {
                        return Mono.<Account>error(new FinanceException(403, "无权查看该组织账户"));
                    }
                    return accounts.findByOrganization(orgId)
                            .switchIfEmpty(Mono.error(new FinanceException(404, "账户不存在")));
                })
                .map(a -> ResponseEntity.ok(Map.of("success", true, "data", toBody(a))));
    }

    /** 幂等开户：已存在→既有(false)；否则 create，并发冲突(create empty)→回读既有(false)。 */
    private Mono<Provisioned> provision(String orgId) {
        return accounts.findByOrganization(orgId)
                .<Provisioned>map(a -> new Provisioned(a, false))
                .switchIfEmpty(accounts.create(orgId)
                        .<Provisioned>map(a -> new Provisioned(a, true))
                        .switchIfEmpty(accounts.findByOrganization(orgId)
                                .<Provisioned>map(a -> new Provisioned(a, false))));
    }

    private EventEnvelope envelope(String eventType, Account account) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", account.id());
        payload.put("organizationId", account.organizationId());
        payload.put("balanceCents", account.balanceCents());
        return new EventEnvelope(UUID.randomUUID().toString(), eventType, "Account",
                account.id(), 1, Instant.now(), null, payload);
    }

    private Map<String, Object> toBody(Account account) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", account.id());
        m.put("organizationId", account.organizationId());
        m.put("balanceCents", account.balanceCents());
        m.put("currency", account.currency());
        m.put("createdAt", account.createdAt() == null ? null : account.createdAt().toString());
        return m;
    }

    private record Provisioned(Account account, boolean created) {}

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new FinanceException(status, message));
    }
}
