package com.grassland.edge;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.edge.internalassertion.InternalAssertionFilter;
import com.grassland.edge.internalassertion.SessionIdentityResolver;
import com.grassland.edge.internalassertion.VideoRecreationRateLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 启用 session 直读（{@code edge.identity.from-database-url=true}）时上下文必须能装配起来。
 *
 * <p>回归自 Slice 12 Stage 5：{@link VideoRecreationRateLimitFilter} 有两个构造器且都未标注
 * {@code @Autowired}，Spring 转而寻找无参构造器，容器启动即
 * {@code NoSuchMethodException: <init>()}。既有测试全是单测/无该 flag 的上下文测试，
 * 加之 edge-bff 镜像长期未重建，这个缺陷从 Slice 9 起一直藏着，直到本轮重建镜像才崩在启动。
 *
 * <p>故这里断言的是「**这组 conditional bean 真的被实例化过**」——只跑纯代理形态的上下文测试
 * 无法覆盖它们。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EdgeIdentityEnabledContextIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("edge.identity.from-database-url", () -> "true");
        // InternalAssertionFilter（由 from-database-url 激活）依赖 IdentityAssertionSigner，
        // 后者由 identity-assertion.enabled=true 提供——生产里两者同开，测试也要同开。
        registry.add("identity-assertion.enabled", () -> "true");
        registry.add("identity-assertion.secret", () -> "test-assertion-secret-32-chars-min!!");
        // 直给 spring.r2dbc.url，避开 EdgeR2dbcConfig 的 DATABASE_URL→r2dbc 改写分支（那条另有单测覆盖）
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://"
                + POSTGRES.getUsername() + ":" + POSTGRES.getPassword() + "@"
                + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName());
        registry.add("identity.legacy.session.secret", () -> "test-secret-32-chars-minimum!!!");
        registry.add("identity.legacy.session.cookie-name", () -> "y1.sid");
    }

    @Autowired(required = false)
    private VideoRecreationRateLimitFilter videoRecreationRateLimitFilter;

    @Autowired(required = false)
    private SessionIdentityResolver sessionIdentityResolver;

    @Autowired(required = false)
    private InternalAssertionFilter internalAssertionFilter;

    @Test
    void wiresSessionBackedIdentityBeans() {
        assertThat(sessionIdentityResolver).isNotNull();
        assertThat(videoRecreationRateLimitFilter).isNotNull();
        assertThat(internalAssertionFilter).isNotNull();
    }
}
