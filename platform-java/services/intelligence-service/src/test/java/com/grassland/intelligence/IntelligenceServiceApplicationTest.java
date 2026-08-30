package com.grassland.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static com.grassland.identity.assertion.TestAssertionHelper.registerServiceKeyring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 纯 context 启动：r2dbc 占位 url（DatabaseClient 装配，免真实 DB）+ identity-assertion（signer 装配）
 *  + outbox 关。任务书 #58：零模型 env 也能起（受信 origin 缓存预热失败仅告警 fail-closed，不阻断）。 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.r2dbc.url=r2dbc:postgresql://u:p@localhost:1/nonexistent",
        "intelligence.outbox.enabled=false",
        "object-storage.enabled=false",
})
class IntelligenceServiceApplicationTest {
    @DynamicPropertySource
    static void assertionKeys(DynamicPropertyRegistry registry) {
        registerServiceKeyring(registry, "intelligence");
    }

    @Test
    void startsApplicationContext(ApplicationContext context) {
        assertThat(context).isNotNull();
    }
}
