package com.grassland.trust.security;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 服务间断言签发器（GL-P0-ASSERT-001 keyring 模式）。
 *
 * <p>trust 现签 {@code callerKind="service"} + {@code principal="trust"} 断言（带 org 上下文），
 * 用于跨服务调 finance 的 release/capture/reverse（争议终局钱侧分派）。
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
    private final String issuer;

    public TrustServiceAssertionIssuer(IdentityAssertionSigner signer,
                                       @Value("${identity-assertion.issuer:trust}") String issuer) {
        this.signer = signer;
        this.issuer = issuer;
    }

    /**
     * 现签一个带 org 上下文的 trust 服务断言（按目标受众选钥）。
     *
     * @param organizationId 组织 ID（可选，用于 org 级授权）
     * @param targetAudience 目标受众服务名（如 grassland-finance）
     * @return 已签 token
     */
    public String issueForOrg(String organizationId, String targetAudience) {
        return sign(organizationId, targetAudience);
    }

    /**
     * 现签一个不带 org 上下文的 trust 服务断言（Slice 12 安全收口）。
     *
     * <p>用于 trust→marketplace 的争议参与方授权：canonical organization 由 marketplace 从 task 读取并返回，
     * trust 不应在请求时断言任何 org（否则又把组织上下文交回不可信的调用方）。
     *
     * @param targetAudience 目标受众服务名（grassland-marketplace）
     * @return 已签 token
     */
    public String issueService(String targetAudience) {
        return sign(null, targetAudience);
    }

    private String sign(String organizationId, String targetAudience) {
        Instant now = Instant.now();
        IdentityAssertion base = new IdentityAssertion(
                "service:" + PRINCIPAL, null, null, organizationId, null,
                "service", "internal", null,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                targetAudience, now, now.plusSeconds(TTL_SECONDS),
                "service", PRINCIPAL, null);
        return signer.sign(base, targetAudience);
    }
}
