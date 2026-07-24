package com.grassland.finance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/** 纯 context 启动测试。Slice 4D：补 r2dbc 占位 url（让 DatabaseClient 装配，免真实 DB）
 *  + identity-assertion（让 signer 装配，FinanceCallerResolver 依赖）。 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.r2dbc.url=r2dbc:postgresql://u:p@localhost:1/nonexistent",
        "identity-assertion.enabled=true",
        "identity-assertion.secret=test-secret-32-chars-min!!!"
})
class FinanceServiceApplicationTest {
    @Test
    void startsApplicationContext(ApplicationContext context) {
        assertThat(context).isNotNull();
    }
}
