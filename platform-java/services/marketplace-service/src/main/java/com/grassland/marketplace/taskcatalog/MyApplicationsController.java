package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 推荐官「我的报名」历史列表（任务书 #29+#30 Stage 2 / D5）。
 *
 * <p>{@code GET /api/tasks/my-applications?status=&cursor=&limit=} — 跨任务列出当前推荐官的报名，
 * join task 取标题/状态/赏金。self-scoped：recommenderAccountId 烧进 SQL WHERE（HLD 7.4），
 * 任何人只能看到自己的报名。keyset 游标分页（{@code created_at DESC, id DESC}），形状同 feed
 * {@code {items, nextCursor, hasMore}}。
 *
 * <p>路由挂在既有 {@code /api/tasks/**} 前缀下（edge-bff 已注册，不新增公网前缀，D5）；
 * 字面量段 {@code my-applications} 在 PathPattern 优先于 {@code {id}} 模板，不会被任务详情抢走。
 *
 * <p>用途：推荐官主页历史任务表；前端并以此 join finance {@code byEngagement} 出任务标题（D3）。
 */
@RestController
public class MyApplicationsController {

    private final MarketplaceCallerResolver callers;
    private final TaskApplicationRepository apps;

    public MyApplicationsController(MarketplaceCallerResolver callers, TaskApplicationRepository apps) {
        this.callers = callers;
        this.apps = apps;
    }

    @GetMapping("/api/tasks/my-applications")
    public Mono<ResponseEntity<Map<String, Object>>> myApplications(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "20") int limit,
            ServerHttpRequest request) {
        String normalizedStatus = blankToNull(status);
        if (normalizedStatus != null) {
            try {
                normalizedStatus = ApplicationStatus.fromDb(normalizedStatus).dbValue();
            } catch (IllegalArgumentException e) {
                return Mono.error(new MarketplaceException(400, "不支持的报名状态筛选：" + status));
            }
        }
        final String statusFilter = normalizedStatus;
        int safeLimit = Math.max(1, Math.min(limit, 50));
        MyCursor decoded = MyCursor.decode(cursor);
        return callers.requireRecommender(request)
                .flatMap(rec -> apps.findMyApplications(rec.accountId(), statusFilter,
                                decoded == null ? null : decoded.ts(),
                                decoded == null ? null : decoded.id(), safeLimit + 1)
                        .collectList()
                        .map(rows -> body(rows, safeLimit)));
    }

    /** 组装分页体：取 limit+1 判 hasMore，nextCursor 为本页最后一行的 (created_at, id)。 */
    private ResponseEntity<Map<String, Object>> body(
            List<TaskApplicationRepository.MyApplicationRow> rows, int limit) {
        boolean hasMore = rows.size() > limit;
        List<TaskApplicationRepository.MyApplicationRow> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore && !page.isEmpty() ? MyCursor.encode(page.get(page.size() - 1)) : null;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", page.stream().map(MyApplicationsController::itemBody).toList());
        data.put("nextCursor", nextCursor);
        data.put("hasMore", hasMore);
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    private static Map<String, Object> itemBody(TaskApplicationRepository.MyApplicationRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("applicationId", row.applicationId());
        map.put("taskId", row.taskId());
        map.put("taskTitle", row.taskTitle());
        map.put("taskStatus", row.taskStatus());
        map.put("applicationStatus", row.applicationStatus());
        map.put("bountyCents", row.bountyCents());
        map.put("appliedAt", row.appliedAt() == null ? null : row.appliedAt().toString());
        map.put("settledAt", row.settledAt() == null ? null : row.settledAt().toString());
        return map;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** keyset 游标：opaque base64url 编码 {@code createdAt|id}（镜像 feed FeedCursor；坏游标当首页）。 */
    record MyCursor(Instant ts, String id) {
        static String encode(TaskApplicationRepository.MyApplicationRow row) {
            String raw = row.appliedAt().toString() + "|" + row.applicationId();
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static MyCursor decode(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int sep = raw.lastIndexOf('|');
                if (sep <= 0 || sep == raw.length() - 1) {
                    return null;
                }
                return new MyCursor(Instant.parse(raw.substring(0, sep)), raw.substring(sep + 1));
            } catch (Exception error) {
                return null;
            }
        }
    }
}
