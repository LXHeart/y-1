package com.grassland.marketplace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/** 纯 context 启动测试：禁用 object-storage（免 MinIO）+ temporal test-server（内存，免 temporal 容器）。 */
@SpringBootTest
@TestPropertySource(properties = {
        "object-storage.enabled=false",
        "spring.temporal.test-server.enabled=true"
})
class MarketplaceServiceApplicationTest {
    @Test
    void startsApplicationContext(ApplicationContext context) {
        assertThat(context).isNotNull();
    }
}
