package com.grassland.identity.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.grassland.identity.event.IdentityEventEnvelope;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 把一条身份域事件解析成「该通知谁」。草场 Slice 12 Stage 2。
 *
 * <p>全部查 identity 自己的表（app_users / organization_membership / merchant_permission_request），
 * <b>不跨服务调用</b>——identity 是最上游服务，收件人来源都在本地。
 *
 * <p>解析规则：
 * <ul>
 *   <li>{@code MembershipInvited} / {@code MembershipInvitationRevoked}：payload 只有邀请邮箱 →
 *       按归一化小写查 app_users；<b>查不到 → 静默跳过</b>（未注册用户靠邮件，且不得因此报错重试阻塞分区）。</li>
 *   <li>{@code MembershipInvitationAccepted} / {@code Declined}：通知 org 的 owner/admin，排除操作者本人。</li>
 *   <li>{@code MembershipGranted}：直接通知 payload.accountId。</li>
 *   <li>{@code PermissionRequested}：通知 org 的 owner/admin，排除申请人。</li>
 *   <li>{@code PermissionReviewed}：payload 只有 orgId+decision → 用 aggregateId（= 权限申请 id）
 *       回查 merchant_permission_request.requester_account_id，通知申请人。</li>
 * </ul>
 */
@Component
public class NotificationRecipientResolver {

    private final DatabaseClient db;

    public NotificationRecipientResolver(DatabaseClient db) {
        this.db = db;
    }

    /**
     * @return 去重后的收件人 accountId 列表；空列表 = 无可送达对象（仍写 inbox，但不产生通知）
     */
    Mono<java.util.List<String>> resolve(IdentityEventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        return switch (envelope.eventType()) {
            case "MembershipInvited", "MembershipInvitationRevoked" ->
                    text(payload, "email")
                            .flatMap(this::findAccountIdByEmail)
                            .map(java.util.List::of)
                            .defaultIfEmpty(java.util.List.of());
            case "MembershipInvitationAccepted", "MembershipInvitationDeclined" ->
                    orgManagersExcluding(payload, "accountId");
            case "MembershipGranted" ->
                    text(payload, "accountId")
                            .map(java.util.List::of)
                            .defaultIfEmpty(java.util.List.of());
            case "PermissionRequested" ->
                    orgManagersExcluding(payload, "requesterAccountId");
            case "PermissionReviewed" ->
                    findPermissionRequester(envelope.aggregateId())
                            .map(java.util.List::of)
                            .defaultIfEmpty(java.util.List.of());
            default -> Mono.just(java.util.List.of());
        };
    }

    /** org 的 owner+admin，排除操作者本人（操作者不需要被通知自己刚做的动作）。 */
    private Mono<java.util.List<String>> orgManagersExcluding(JsonNode payload, String actorField) {
        return text(payload, "organizationId").flatMap(orgId -> {
            Mono<java.util.List<String>> managers = findOrgManagerAccountIds(orgId).collectList();
            return text(payload, actorField)
                    .map(actor -> excludeActor(managers, actor))
                    .defaultIfEmpty(managers)
                    .flatMap(m -> m);
        }).defaultIfEmpty(java.util.List.of());
    }

    private static Mono<java.util.List<String>> excludeActor(
            Mono<java.util.List<String>> managers, String actorId) {
        return managers.map(list -> {
            Set<String> deduped = new LinkedHashSet<>(list);
            deduped.remove(actorId);
            return new java.util.ArrayList<>(deduped);
        });
    }

    private Mono<String> findAccountIdByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Mono.empty();
        }
        // email 归一化小写存储（V8 约定），两侧都归一化比较。
        return db.sql("SELECT id::text FROM app_users WHERE lower(email) = lower(:email)")
                .bind("email", email.trim())
                .map(row -> row.get("id", String.class))
                .one();
    }

    private Flux<String> findOrgManagerAccountIds(String organizationId) {
        return db.sql("""
                        SELECT account_id::text FROM organization_membership
                        WHERE organization_id = CAST(:org AS uuid) AND role IN ('owner', 'admin')
                        """)
                .bind("org", organizationId)
                .map(row -> row.get("account_id", String.class))
                .all();
    }

    private Mono<String> findPermissionRequester(String requestId) {
        return db.sql("SELECT requester_account_id::text FROM merchant_permission_request WHERE id = CAST(:id AS uuid)")
                .bind("id", requestId)
                .map(row -> row.get("requester_account_id", String.class))
                .one();
    }

    private static Mono<String> text(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        return (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank())
                ? Mono.empty() : Mono.just(node.asText());
    }
}
