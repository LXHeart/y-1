package com.grassland.identity.notify.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.grassland.identity.event.IdentityEventEnvelope;
import com.grassland.identity.notification.MailTemplates;
import com.grassland.identity.notification.MailTemplates.MailTemplate;
import java.util.List;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 把一条通知事件入队为邮件（GL-P1-NOTIFY-001 接线胶水）。
 *
 * <p>由 {@code NotificationEventProcessor.emit} 在站内通知插入的<b>同一事务</b>内调用，
 * 保证「通知落库 ⇔ 邮件入队」原子。只对 {@link MailTemplates} 覆盖的高价值子集入队（PERMISSION 等返回 null 跳过）。
 *
 * <h3>收件人解析</h3>
 * <ul>
 *   <li><b>邀请事件</b>（{@code MembershipInvited} / {@code MembershipInvitationRevoked}）：收件人 =
 *       {@code payload.email}——邀请本就发给<b>可能未注册</b>的邮箱，<b>不经</b> accountId→email 转换
 *       （站内通知对未注册邮箱不产生行，但邮件必须发）。</li>
 *   <li><b>其余事件</b>：收件人 = {@code accountId → app_users.email}（{@code NotificationRecipientResolver}
 *       返回的 accountId 逐个反查；无 email 的账号跳过）。</li>
 * </ul>
 */
@Component
public class MailOutboxEnqueuer {

    private final MailOutboxRepository repository;
    private final DatabaseClient db;

    public MailOutboxEnqueuer(MailOutboxRepository repository, DatabaseClient db) {
        this.repository = repository;
        this.db = db;
    }

    /**
     * @param envelope   通知事件
     * @param accountIds 站内通知收件人（resolver 解析）；邀请事件忽略此参数（用 payload.email）
     */
    public Mono<Void> enqueue(IdentityEventEnvelope envelope, List<String> accountIds) {
        MailTemplate template = MailTemplates.mailTemplate(envelope.eventType(), envelope.payload());
        if (template == null) {
            return Mono.empty(); // PERMISSION 或非关注类型 → 不入队邮件
        }

        if (isInvitationByEmail(envelope.eventType())) {
            String email = text(envelope.payload(), "email");
            return email == null ? Mono.empty() : append(envelope.eventId(), email, template);
        }

        if (accountIds == null || accountIds.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(accountIds)
                .concatMap(this::lookupEmail)
                .collectList()
                .flatMap(emails -> {
                    Mono<Void> chain = Mono.empty();
                    for (String email : emails) {
                        chain = chain.then(append(envelope.eventId(), email, template));
                    }
                    return chain;
                });
    }

    /** 邀请类事件：收件人是 payload.email（被邀请人可能未注册）。 */
    private static boolean isInvitationByEmail(String eventType) {
        return "MembershipInvited".equals(eventType) || "MembershipInvitationRevoked".equals(eventType);
    }

    private Mono<String> lookupEmail(String accountId) {
        return db.sql("SELECT email FROM app_users WHERE id = CAST(:id AS uuid) AND email IS NOT NULL")
                .bind("id", accountId)
                .map(row -> row.get("email", String.class))
                .one(); // 无 email → empty，不产生元素
    }

    private Mono<Void> append(String eventId, String email, MailTemplate template) {
        return repository.append(new MailOutboxRepository.MailMessage(
                eventId, email, template.subject(), template.body(), template.category()));
    }

    private static String text(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        return (node == null || !node.isTextual() || node.asText().isBlank()) ? null : node.asText().trim();
    }
}
