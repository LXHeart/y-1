package com.grassland.trust;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * trust context 装配冒烟（草场 Epic 6 Slice 6A）。占位 props（r2dbc url + identity-assertion）让 DB/断言 bean 装配，
 * 不真连库（{@code trust.datasource.from-database-url=false}，Flyway/R2dbcConfig 条件 bean 不激活）。
 */
@SpringBootTest(properties = {
        "spring.r2dbc.url=r2dbc:postgresql://u:p@localhost:5432/test",
        "identity-assertion.enabled=true",
        "identity-assertion.secret=test-secret-32-chars-min!!!",
        "identity-assertion.audience=grassland-internal",
        "management.server.port=0",
        "spring.temporal.test-server.enabled=true"
})
class TrustServiceApplicationTest {

    @Test
    void startsApplicationContext(ApplicationContext context) {
        assertThat(context).isNotNull();
    }
}
