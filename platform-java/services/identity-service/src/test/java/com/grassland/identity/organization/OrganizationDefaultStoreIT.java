package com.grassland.identity.organization;

import com.grassland.identity.IdentityItSupport;
import org.junit.jupiter.api.Test;

/**
 * 注册即开店（任务书 #50 D2）：创建主体同事务自动建默认门店（名=主体名）+ 双 outbox 事件。
 */
class OrganizationDefaultStoreIT extends IdentityItSupport {

    @Test
    void createOrg_seedsDefaultStoreAndBothEvents() {
        var owner = seedAccount("defstore-owner@example.com");
        String orgId = createOrg(owner.cookie(), "默认店主体");

        Long storeCount = db.sql("SELECT COUNT(*)::int AS c FROM store WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", orgId).map(r -> r.get("c", Integer.class)).one().block().longValue();
        org.assertj.core.api.Assertions.assertThat(storeCount).isEqualTo(1);

        String storeName = db.sql("SELECT name FROM store WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", orgId).map(r -> r.get("name", String.class)).one().block();
        org.assertj.core.api.Assertions.assertThat(storeName).isEqualTo("默认店主体");

        Long storeEvents = db.sql("SELECT COUNT(*)::int AS c FROM outbox"
                        + " WHERE event_type = 'StoreCreated' AND payload->>'organizationId' = :org"
                        + " AND payload->>'defaultStore' = 'true'")
                .bind("org", orgId).map(r -> r.get("c", Integer.class)).one().block().longValue();
        org.assertj.core.api.Assertions.assertThat(storeEvents).isEqualTo(1);

        Long orgEvents = db.sql("SELECT COUNT(*)::int AS c FROM outbox"
                        + " WHERE event_type = 'OrganizationCreated' AND payload->>'organizationId' = :org")
                .bind("org", orgId).map(r -> r.get("c", Integer.class)).one().block().longValue();
        org.assertj.core.api.Assertions.assertThat(orgEvents).isEqualTo(1);
    }
}
