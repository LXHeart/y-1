package com.grassland.trust.security;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 服务间断言签发器（草场 Epic 6 Slice 6C Phase D / HLD 11.1）。trust 现签 {@code callerKind="service"} +
 * {@code principal="trust"} 断言（带 org 上下文），用于跨服务调 finance 的 release/capture/reverse（争议终局钱侧分派）。
 *
 * <p>每请求现签（TTL 30s）：规避用户断言 60s TTL 在 Temporal 重试链路下过期。finance 端
 * {@code FinanceCallerResolver.resolveMerchantOrService(s)/authorizeForOrg} 验签 + 校验 principal/org。
 * 镜像 marketplace {@code ServiceAssertionIssuer}。
 */
@Component
public class TrustServiceAssertionIssuer {

    public static final String PRINCIPAL = "trust";

    private static final long TTL_SECONDS = 30;

    private final IdentityAssertionSigner signer;
    private final String audience;

    public TrustServiceAssertionIssuer(IdentityAssertionSigner signer,
                                       @Value("${identity-assertion.audience:grassland-internal}") String audience) {
        this.signer = signer;
        this.audience = audience;
    }

    /** 现签一个带 org 上下文的 trust 服务断言。 */
    public String issueForOrg(String organizationId) {
        return sign(organizationId);
    }

    /**
     * 现签一个不带 org 上下文的 trust 服务断言（Slice 12 安全收口）。
     *
     * <p>用于 trust→marketplace 的争议参与方授权：canonical organization 由 marketplace 从 task 读取并返回，
     * trust 不应在请求时断言任何 org（否则又把组织上下文交回不可信的调用方）。
     */
    public String issueService() {
        return sign(null);
    }

    private String sign(String organizationId) {
        Instant now = Instant.now();
        return signer.sign(new IdentityAssertion(
                "service:" + PRINCIPAL, null, null, organizationId, null,
                "service", "internal", null,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                audience, now, now.plusSeconds(TTL_SECONDS),
                "service", PRINCIPAL));
    }
}
