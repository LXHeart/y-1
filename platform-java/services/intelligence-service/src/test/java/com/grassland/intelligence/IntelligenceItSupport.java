package com.grassland.intelligence;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * intelligence 集成测试公共基座（草场 intelligence Slice 1）。镜像 marketplace 基座。
 *
 * <p>单例 testcontainers postgres（{@code intelligence.datasource.from-database-url=true} → Flyway 建 outbox）；
 * WireMock 托管平台默认 Qwen（{@code /chat/completions}）；{@code identity-assertion} 注入 signer；
 * {@code intelligence.outbox.enabled=false} 关掉发布器（默认 kafka bootstrap 解析不到会在事件循环线程阻塞，
 * 饿死 WebFlux——各 IT 断言 outbox 表里的行，从不校验真实投递）。提供 {@link #sign} 签断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntelligenceItSupport {

    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    public static final WireMockServer QWEN = new WireMockServer(0); // 0 = 随机端口，start 后取实际端口

    static {
        POSTGRES.start();
        QWEN.start();
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected IdentityAssertionSigner signer;

    @Autowired
    protected DatabaseClient db;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String dbUrl = "postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                + "@" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName();
        r.add("intelligence.datasource.from-database-url", () -> "true");
        r.add("DATABASE_URL", () -> dbUrl);
        r.add("management.server.port", () -> "0");
        r.add("identity-assertion.enabled", () -> "true");
        r.add("identity-assertion.secret", () -> "test-secret-32-chars-min!!!");
        r.add("identity-assertion.audience", () -> "grassland-internal");
        r.add("intelligence.outbox.enabled", () -> "false");
        r.add("ai.credit-compensation.enabled", () -> "false");
        // 未启 MinIO：回落 LocalGeneratedImageStore（本地卷），S3 自动配置与 S3GeneratedImageStore 不装配。
        r.add("object-storage.enabled", () -> "false");
        // 平台默认 Qwen 指向 WireMock：base-url 是主机名（localhost）→ SSRF 结构校验通过（IP 字面量才拒）。
        r.add("ai.qwen.base-url", QWEN::baseUrl);
        r.add("ai.qwen.api-key", () -> "sk-test");
        r.add("ai.qwen.model", () -> "qwen-plus");
        r.add("ai.platform-model.allow-insecure-loopback", () -> "true");
    }

    protected WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    /** 签一个断言（org/tier/role 为 null——冒烟端点只需任意登录用户）。 */
    protected String sign(String accountId, String activeIdentityType) {
        return signWithRole(accountId, activeIdentityType, null, null);
    }

    /** 签带 role 的断言（GL-P3-AI-001：requireAdmin / 组织作用域测试用）。role 经 16 参构造器。 */
    protected String signWithRole(String accountId, String activeIdentityType, String organizationId, String role) {
        Instant now = Instant.now();
        return signer.sign(new IdentityAssertion(
                accountId, activeIdentityType, "sid-" + accountId, organizationId, null,
                "cookie-session", "level1", null, "r", "t",
                "grassland-internal", now, now.plusSeconds(60), null, null, role));
    }

    /** 平台管理员断言（role=admin）。 */
    protected String signAdmin(String accountId) {
        return signWithRole(accountId, null, null, "admin");
    }

    /** 组织成员断言（商家活动身份 + org）。 */
    protected String signWithOrg(String accountId, String organizationId) {
        return signWithRole(accountId, "merchant", organizationId, null);
    }
}
