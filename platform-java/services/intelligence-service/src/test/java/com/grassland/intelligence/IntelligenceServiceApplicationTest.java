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
 *  + 平台默认 Qwen（PlatformModelConfig @PostConstruct 校验通过；base-url 主机名经 SSRF 结构校验）+ outbox 关。 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.r2dbc.url=r2dbc:postgresql://u:p@localhost:1/nonexistent",
        "intelligence.outbox.enabled=false",
        "object-storage.enabled=false",
        "ai.qwen.base-url=https://dashscope.aliyuncs.com",
        "ai.qwen.api-key=sk-synthetic-context-test-key"
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
