package com.grassland.identity.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;

import com.grassland.identity.IdentityItSupport;
import com.grassland.identity.event.EventEnvelope;
import com.grassland.identity.event.OutboxRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Mono;

/**
 * Slice 7C：证明 identity controller 写路径的「领域写 + outbox append」在同一 R2DBC 事务。
 *
 * <p>用 {@code @MockitoSpyBean} 把 {@link OutboxRepository#append} 针对某事件类型注入失败，
 * 断言领域写（组织 / 门店）随之回滚。覆盖 create（switchIfEmpty 内单写）+ store 两种写形态；
 * 其余 controller（membership/identityProfile/permission/invitation/register）同形态
 * （均 {@code transactions.transactional(写+outbox)}），由既有 IT 守 happy path。
 */
class OutboxAtomicityIT extends IdentityItSupport {

    @MockitoSpyBean
    OutboxRepository outbox;

    @Test
    void createOrgRollsBackWhenOutboxFails() {
        Seeded owner = seedAccount("org-fail-" + UUID.randomUUID() + "@example.com");

        failOutboxOn("OrganizationCreated");
        client().post().uri("/api/organizations")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"name\":\"X\"}")
                .exchange().expectStatus().is5xxServerError();

        assertThat(orgCountByOwner(owner.accountId())).isZero();   // 组织未建
    }

    @Test
    void createStoreRollsBackWhenOutboxFails() {
        Seeded owner = seedAccount("store-fail-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "X");   // outbox 正常

        failOutboxOn("StoreCreated");
        client().post().uri("/api/organizations/" + orgId + "/stores")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"name\":\"S\"}")
                .exchange().expectStatus().is5xxServerError();

        assertThat(storeCountByOrg(orgId)).isZero();   // 门店未建
    }

    private void failOutboxOn(String eventType) {
        doReturn(Mono.<Void>error(new RuntimeException("outbox injected failure")))
                .when(outbox).append(argThat((EventEnvelope e) -> e != null && eventType.equals(e.eventType())));
    }

    private long orgCountByOwner(String ownerId) {
        Long c = db.sql("SELECT COUNT(*)::bigint AS c FROM organization WHERE owner_account_id = CAST(:o AS uuid)")
                .bind("o", ownerId).map(row -> row.get("c", Long.class)).one().block();
        return c == null ? 0L : c;
    }

    private long storeCountByOrg(String orgId) {
        Long c = db.sql("SELECT COUNT(*)::bigint AS c FROM store WHERE organization_id = CAST(:o AS uuid)")
                .bind("o", orgId).map(row -> row.get("c", Long.class)).one().block();
        return c == null ? 0L : c;
    }
}
