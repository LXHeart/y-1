package com.grassland.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static com.grassland.identity.assertion.TestAssertionHelper.registerServiceKeyring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import io.temporal.opentracing.OpenTracingClientInterceptor;
import io.temporal.opentracing.OpenTracingWorkerInterceptor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * trust context 装配冒烟（草场 Epic 6 Slice 6A）。占位 props（r2dbc url + identity-assertion）让 DB/断言 bean 装配，
 * 不真连库（{@code trust.datasource.from-database-url=false}，Flyway/R2dbcConfig 条件 bean 不激活）。
 */
@SpringBootTest(properties = {
        "spring.r2dbc.url=r2dbc:postgresql://u:p@localhost:5432/test",
        "management.server.port=0",
        "trust.evidence.pseudonym-secret=test-trust-evidence-pseudonym-secret-32-chars",
        "spring.temporal.test-server.enabled=true",
        // marketplace 声誉消费者指向真 broker——冒烟无 Kafka，关闭（镜像 TrustItSupport）。
        "trust.marketplace-consumer.enabled=false"
})
class TrustServiceApplicationTest {
    @DynamicPropertySource
    static void assertionKeys(DynamicPropertyRegistry registry) {
        registerServiceKeyring(registry, "trust");
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
