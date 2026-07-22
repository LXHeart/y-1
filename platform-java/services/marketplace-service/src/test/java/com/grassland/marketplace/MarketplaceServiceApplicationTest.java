package com.grassland.marketplace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/** 纯 context 启动测试：禁用 object-storage 以免需要 MinIO。带存储的端到端见 StorageUploadControllerIT。 */
@SpringBootTest
@TestPropertySource(properties = "object-storage.enabled=false")
class MarketplaceServiceApplicationTest {
    @Test
    void startsApplicationContext(ApplicationContext context) {
        assertThat(context).isNotNull();
    }
}
