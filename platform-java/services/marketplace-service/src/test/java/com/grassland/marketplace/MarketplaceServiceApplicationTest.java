package com.grassland.marketplace;

import static org.assertj.core.api.Assertions.assertThat;
import static com.grassland.identity.assertion.TestAssertionHelper.registerServiceKeyring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import io.temporal.opentracing.OpenTracingClientInterceptor;
import io.temporal.opentracing.OpenTracingWorkerInterceptor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 纯 context 启动测试：禁用 object-storage（免 MinIO）+ temporal test-server（内存，免 temporal 容器）。
 *  Slice 4A：补 r2dbc 占位 url（让 DatabaseClient 装配，免真实 DB）+ identity-assertion（让 signer 装配）。 */
@SpringBootTest
@TestPropertySource(properties = {
        "object-storage.enabled=false",
        "spring.temporal.test-server.enabled=true",
        "spring.r2dbc.url=r2dbc:postgresql://u:p@localhost:1/nonexistent"
})
class MarketplaceServiceApplicationTest {
    @DynamicPropertySource
    static void assertionKeys(DynamicPropertyRegistry registry) {
        registerServiceKeyring(registry, "marketplace");
    }

    @Test
    void startsApplicationContext(ApplicationContext context) {
        assertThat(context).isNotNull();
        assertThat(context.getBeansOfType(WorkflowClientInterceptor.class).values())
                .anyMatch(OpenTracingClientInterceptor.class::isInstance);
        assertThat(context.getBeansOfType(WorkerInterceptor.class).values())
                .anyMatch(OpenTracingWorkerInterceptor.class::isInstance);
    }
}
