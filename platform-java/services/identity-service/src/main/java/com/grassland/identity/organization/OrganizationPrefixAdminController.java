package com.grassland.identity.organization;

import com.grassland.identity.admin.PageEnvelope;
import com.grassland.identity.auth.IdentityException;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 商家主体成员账号前缀的运营端点（任务书 #51 第 1 条）。
 *
 * <p><b>为什么收归运营</b>：前缀自动生成、商家只读；改前缀不是一次设置变更，而是会
 * <b>连带改掉该主体下全部成员的登录名</b>（旧登录名立即失效）的对外可见动作，属平台处置。
 * 商家侧的 {@code PATCH /api/organizations/{id}/account-prefix} 已随本任务书删除。
 *
 * <p>独立控制器：{@link OrganizationController} 有类级 {@code @RequestMapping("/api/organizations")}
 * 前缀，绝对路径会被拼坏（同 {@link OrganizationRenameAdminController} 的理由）。
 *
 * <ul>
 * <li>GET /api/admin/organizations?q= — 主体搜索（定位改名目标；回成员数 = 影响面）</li>
 * <li>PATCH /api/admin/organizations/{id}/account-prefix — 改前缀 + 连带重写</li>
 * </ul>
 *
 * <p>两端点均经 {@link CurrentAccountResolver#requireAdmin}（{@code backend_role} 为唯一权威，
 * 与 KYB/权限/更名审核同口径）。identity 没有全局 SecurityWebFilterChain——漏掉这一行就是
 * 完全无鉴权。
 */
@RestController
public class OrganizationPrefixAdminController {

    /** 前缀规则（与 #49 建号侧登录名各段同规则）。 */
    private static final String PREFIX_PATTERN = "^[a-z0-9]{3,24}$";

    private final CurrentAccountResolver accounts;
    private final OrganizationRepository organizations;
    private final OrganizationPrefixRewriteRepository rewrites;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;

    public OrganizationPrefixAdminController(CurrentAccountResolver accounts,
            OrganizationRepository organizations, OrganizationPrefixRewriteRepository rewrites,
            OutboxRepository outbox, TransactionalOperator transactions) {
        this.accounts = accounts;
        this.organizations = organizations;
        this.rewrites = rewrites;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    @GetMapping("/api/admin/organizations")
    public Mono<ResponseEntity<Map<String, Object>>> search(@RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset,
            ServerHttpRequest request) {
        int pageSize = PageEnvelope.limit(limit);
        int pageOffset = PageEnvelope.offset(offset);
        String query = searchQuery(q);
        return accounts.requireAdmin(request)
                .flatMap(admin -> Mono.zip(rewrites.searchForAdmin(query, pageSize, pageOffset),
                        rewrites.countSearchForAdmin(query))
                        .map(tuple -> ResponseEntity.ok(Map.of("success", true, "data", PageEnvelope
                                .data(tuple.getT1().stream().map(this::toBody).toList(), tuple.getT2(),
                                        pageSize, pageOffset)))));
    }

    /**
     * 改前缀 + 连带重写（同事务）：前缀列 → 登录名旁表 → 占位邮箱 → outbox。
     *
     * <p>拍板 C：<b>不动会话</b>。会话按 account id 认，已登录成员不掉线；下次登录用新账号名。
     * 旧账号名立即失效，成员如何知晓新名属线下流程（本轮不做站内通知）。
     */
    @PatchMapping(path = "/api/admin/organizations/{id}/account-prefix",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> updatePrefix(@PathVariable String id,
            @RequestBody AccountPrefixRequest body, ServerHttpRequest request) {
        String next = body == null || body.prefix() == null ? "" : body.prefix().trim().toLowerCase();
        if (!next.matches(PREFIX_PATTERN)) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "前缀仅支持 3-24 位字母或数字")));
        }
        return accounts.requireAdmin(request)
                .flatMap(admin -> transactions.transactional(
                        organizations.selectAccountPrefix(id)
                                .switchIfEmpty(Mono.error(new IdentityException(404, "组织不存在")))
                                .flatMap(current -> current.equals(next)
                                        ? Mono.error(new IdentityException(400, "新前缀与当前前缀相同"))
                                        : applyRewrite(id, current, next, admin.id()))))
                .map(result -> ResponseEntity.ok(Map.of("success", true, "data", result)))
                // idx_organization_account_prefix 唯一冲突 = 目标前缀已被其他主体占用
                .onErrorResume(DataIntegrityViolationException.class,
                        error -> Mono.just(ResponseEntity.status(409)
                                .body(Map.of("success", false, "error", "该前缀已被其他主体使用"))));
    }

    /**
     * 顺序刻意：先改前缀列（唯一冲突在此暴露，后续重写不必白做），再重写登录名与占位邮箱，
     * 最后 outbox。全部在调用方的一个事务里——中途失败不能留下「前缀已换、成员登录名还是
     * 旧的」这种登录直接坏掉的半态。
     */
    private Mono<Map<String, Object>> applyRewrite(String orgId, String from, String to, String adminId) {
        return organizations.updateAccountPrefix(orgId, to)
                .flatMap(rows -> rows > 0 ? Mono.just(rows)
                        : Mono.error(new IdentityException(404, "组织不存在")))
                .then(rewrites.rewriteMemberUsernames(orgId, from, to))
                .flatMap(renamedAccounts -> rewrites.rewritePlaceholderEmails(orgId, from, to)
                        .flatMap(renamedEmails -> outbox
                                .append(prefixChangedEvent(orgId, from, to, renamedAccounts, adminId))
                                .thenReturn(resultBody(to, renamedAccounts, renamedEmails))));
    }

    private EventEnvelope prefixChangedEvent(String orgId, String from, String to, long renamedAccounts,
            String adminId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", orgId);
        payload.put("fromPrefix", from);
        payload.put("toPrefix", to);
        payload.put("rewrittenAccountCount", renamedAccounts);
        payload.put("changedBy", adminId);
        return new EventEnvelope(UUID.randomUUID().toString(), "OrganizationAccountPrefixChanged",
                "Organization", orgId, 1, Instant.now(), null, payload);
    }

    /** 回显影响面：运营需要看到「改掉了几个人的登录名」才能判断要不要线下通知。 */
    private static Map<String, Object> resultBody(String prefix, long renamedAccounts, long renamedEmails) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("prefix", prefix);
        data.put("rewrittenAccounts", renamedAccounts);
        data.put("rewrittenPlaceholderEmails", renamedEmails);
        return data;
    }

    private Map<String, Object> toBody(OrganizationPrefixRewriteRepository.AdminOrganizationRow row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", row.id());
        body.put("name", row.name());
        body.put("accountPrefix", row.accountPrefix());
        body.put("status", row.status());
        body.put("memberCount", row.memberCount());
        return body;
    }

    /**
     * 搜索词归一（照抄 {@code AdminUserController.searchQuery} 口径）：空白视为不筛；
     * 转义 LIKE 元字符后包 {@code %}，避免运营输入的 {@code %}/{@code _} 变成通配。
     */
    private static String searchQuery(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String escaped = value.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    @ExceptionHandler(IdentityException.class)
    ResponseEntity<Map<String, Object>> handle(IdentityException error) {
        return ResponseEntity.status(error.status())
                .body(Map.of("success", false, "error", error.getMessage()));
    }

    /** 改前缀请求体。 */
    public record AccountPrefixRequest(String prefix) {
    }
}
