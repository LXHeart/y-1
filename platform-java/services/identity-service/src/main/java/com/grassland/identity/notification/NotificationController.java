package com.grassland.identity.notification;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.organization.CurrentAccountResolver;
import com.grassland.identity.user.AuthUser;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 收件箱入口。挂 {@code /api/me/notifications}。草场 Slice 12。
 *
 * <p>所有读写都以 {@code accounts.resolve(request)} 拿到的当前账号为收件人，越权在 repo 的 SQL 层再封一道
 * （见 {@link NotificationRepository}）。列表走 {@code created_at DESC} keyset 分页，新通知持续插表头也不会让下一页重复。
 *
 * <p>响应信封与 {@code MyInvitationController} 一致：{@code {success, data}} / {@code {success:false, error}}。
 */
@RestController
@RequestMapping("/api/me/notifications")
public class NotificationController {

    /** {@code ids} 上限，防超长 IN/数组请求把更新撑大。 */
    private static final int MAX_READ_BATCH = 100;

    private final CurrentAccountResolver accounts;
    private final NotificationRepository notifications;

    public NotificationController(CurrentAccountResolver accounts, NotificationRepository notifications) {
        this.accounts = accounts;
        this.notifications = notifications;
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> list(ServerHttpRequest request) {
        QueryParams query = QueryParams.parse(request);
        return accounts.resolve(request).flatMap(account ->
                notifications.findByAccount(account.id(), query.unreadOnly, query.limit, query.before, query.beforeId)
                        .collectList()
                        .flatMap(items -> notifications.countUnread(account.id())
                                .map(unread -> ResponseEntity.ok(Map.of("success", true,
                                        "data", buildPage(items, query.effectiveLimit(), unread))))));
    }

    @GetMapping("/unread-count")
    public Mono<ResponseEntity<Map<String, Object>>> unreadCount(ServerHttpRequest request) {
        return accounts.resolve(request).flatMap(account ->
                notifications.countUnread(account.id())
                        .map(unread -> ResponseEntity.ok(Map.of("success", true,
                                "data", Map.of("unreadCount", unread)))));
    }

    @PostMapping("/read")
    public Mono<ResponseEntity<Map<String, Object>>> read(@RequestBody MarkNotificationsReadRequest body,
                                                          ServerHttpRequest request) {
        return accounts.resolve(request).flatMap(account ->
                notifications.markRead(account.id(), validateIds(body))
                        .map(updated -> ResponseEntity.ok(Map.<String, Object>of("success", true,
                                "data", Map.of("updated", updated)))));
    }

    @PostMapping("/read-all")
    public Mono<ResponseEntity<Map<String, Object>>> readAll(ServerHttpRequest request) {
        return accounts.resolve(request).flatMap(account ->
                notifications.markAllRead(account.id())
                        .map(updated -> ResponseEntity.ok(Map.<String, Object>of("success", true,
                                "data", Map.of("updated", updated)))));
    }

    private static List<String> validateIds(MarkNotificationsReadRequest body) {
        if (body == null || body.ids() == null || body.ids().isEmpty()) {
            throw new IdentityException(400, "ids 不能为空");
        }
        if (body.ids().size() > MAX_READ_BATCH) {
            throw new IdentityException(400, "ids 不能超过 " + MAX_READ_BATCH + " 条");
        }
        for (String id : body.ids()) {
            try {
                UUID.fromString(id);
            } catch (IllegalArgumentException error) {
                throw new IdentityException(400, "无效的通知 id：" + id);
            }
        }
        return body.ids();
    }

    /**
     * 分页结果。仅当本页**可能未到末尾**（返回数等于请求页大小）时才给 {@code nextBefore}/{@code nextBeforeId}
     * 游标；返回不足一页时置 null，客户端据此停止翻页。{@code unreadCount} 随每页返回，前端无需再发一次请求。
     */
    private static Map<String, Object> buildPage(List<Notification> items, int effectiveLimit, long unreadCount) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", items.stream().map(NotificationController::toBody).toList());
        page.put("unreadCount", unreadCount);
        boolean hasMore = items.size() >= effectiveLimit && !items.isEmpty();
        if (hasMore) {
            Notification last = items.get(items.size() - 1);
            page.put("nextBefore", last.createdAt() == null ? null : last.createdAt().toString());
            page.put("nextBeforeId", last.id());
        } else {
            page.put("nextBefore", null);
            page.put("nextBeforeId", null);
        }
        return page;
    }

    private static Map<String, Object> toBody(Notification n) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", n.id());
        map.put("category", n.category().dbValue());
        map.put("eventType", n.eventType());
        map.put("title", n.title());
        map.put("body", n.body());
        map.put("linkPath", n.linkPath());
        map.put("read", n.isRead());
        map.put("payload", n.payload());
        map.put("createdAt", n.createdAt() == null ? null : n.createdAt().toString());
        return map;
    }

    /** 查询参数解析与校验。{@code limit} 夹取、{@code before}/{@code beforeId} 成对校验。 */
    record QueryParams(boolean unreadOnly, Integer limit, Instant before, String beforeId) {
        static QueryParams parse(ServerHttpRequest request) {
            Map<String, List<String>> q = request.getQueryParams();
            boolean unreadOnly = Boolean.parseBoolean(first(q, "unreadOnly"));
            Integer limit = parseInt(first(q, "limit"));
            Instant before = parseInstant(first(q, "before"));
            String beforeId = first(q, "beforeId");
            if (before != null && beforeId == null) {
                throw new IdentityException(400, "before 与 beforeId 必须同时提供");
            }
            return new QueryParams(unreadOnly, limit, before, beforeId);
        }

        /** 夹取后的实际页大小——与 repo 同款夹取，控制器据此判断是否还有下一页。 */
        int effectiveLimit() {
            return NotificationRepository.clampLimit(limit);
        }

        private static String first(Map<String, List<String>> q, String key) {
            List<String> values = q.get(key);
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        private static Integer parseInt(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException error) {
                throw new IdentityException(400, "limit 必须是整数");
            }
        }

        private static Instant parseInstant(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Instant.parse(value);
            } catch (Exception error) {
                throw new IdentityException(400, "before 必须是 ISO-8601 时间");
            }
        }
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }
}
