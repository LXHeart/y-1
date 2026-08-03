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
            default -> Mono.just(externalRecipients(envelope.eventType(), payload));
        };
    }

    /**
     * 外部服务事件（marketplace / trust / finance）的收件人。草场 Slice 12 Stage 3。
     *
     * <p><b>只读 payload 里已有的 accountId</b>——identity 没有 task / dispute / reservation 表，
     * 也不反向调用下游做领域查询（identity 是最上游服务）。缺字段 → 空列表 → 该事件不产生通知，
     * 但 inbox 仍记录，不会无限重投。所需字段由发射端在 Stage 3 补齐。
     */
    private static java.util.List<String> externalRecipients(String eventType, JsonNode payload) {
        return switch (eventType) {
            // 商家侧：报名/撤回/交付进来了，通知任务归属人。
            case "ApplicationSubmitted", "ApplicationWithdrawn", "DeliverableSubmitted" ->
                    accountIds(payload, "taskOwnerId");
            // 推荐官侧：凭证被退回。
            case "DeliverableRejected" -> accountIds(payload, "recommenderAccountId");
            // 双方都关心：核验结果、结算、结算挂起。
            case "VerificationChecked", "EngagementSettled", "SettlementHeld" ->
                    accountIds(payload, "taskOwnerId", "recommenderAccountId");
            // 商家确认窗口（D-03）：进入窗口通知双方（商家待确认、推荐官知悉）；到期自动结算通知双方。
            case "ConfirmationWindowEntered", "AutoSettledOnTimeout" ->
                    accountIds(payload, "taskOwnerId", "recommenderAccountId");
            // 争议对方通知：marketplace 派生的 EngagementDisputed 携带已解析的对方账号（草场 Slice 12 缺口补全）。
            case "EngagementDisputed" -> accountIds(payload, "counterpartyAccountId");
            // 争议：只有开启人在 trust 本地表内（对方账号缺口见 docs 路线图第 8 项）。
            case "DisputeAssigned", "AdjudicationReopened", "DisputeDecided",
                    "DisputeAppealed", "AdjudicationEscalated", "DisputeFinalized" ->
                    accountIds(payload, "openedByAccountId");
            // 资金：payeeAccountId 是用户账号（不是 finance ledger account）。
            case "FundsReserved", "FundsCaptured", "FundsReleased", "FundsReversed", "AccountCredited" ->
                    accountIds(payload, "payeeAccountId");
            default -> java.util.List.of();
        };
    }

    /** 按字段顺序取出非空 accountId 并去重（同一账号既是任务归属人又是推荐官时只通知一次）。 */
    private static java.util.List<String> accountIds(JsonNode payload, String... fields) {
        Set<String> deduped = new LinkedHashSet<>();
        for (String field : fields) {
            JsonNode node = payload.get(field);
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                deduped.add(node.asText());
            }
        }
        return java.util.List.copyOf(deduped);
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
