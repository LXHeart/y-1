package com.grassland.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.context.ApplicationContext;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.r2dbc.url=r2dbc:postgresql://u:p@localhost:1/nonexistent",
        "identity.outbox.enabled=false"
})
class IdentityServiceApplicationTest {
    @Test
    void startsApplicationContext(ApplicationContext context) {
        assertThat(context).isNotNull();
    }
}
