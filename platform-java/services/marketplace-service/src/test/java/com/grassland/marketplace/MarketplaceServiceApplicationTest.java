package com.grassland.marketplace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/** 纯 context 启动测试：禁用 object-storage（免 MinIO）+ temporal test-server（内存，免 temporal 容器）。
 *  Slice 4A：补 r2dbc 占位 url（让 DatabaseClient 装配，免真实 DB）+ identity-assertion（让 signer 装配）。 */
@SpringBootTest
@TestPropertySource(properties = {
        "object-storage.enabled=false",
        "spring.temporal.test-server.enabled=true",
        "spring.r2dbc.url=r2dbc:postgresql://u:p@localhost:1/nonexistent",
        "identity-assertion.enabled=true",
        "identity-assertion.secret=test-secret-32-chars-min!!!"
})
class MarketplaceServiceApplicationTest {
    @Test
    void startsApplicationContext(ApplicationContext context) {
        assertThat(context).isNotNull();
    }
}
