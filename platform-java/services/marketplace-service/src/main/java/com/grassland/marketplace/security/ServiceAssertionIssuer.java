package com.grassland.marketplace.security;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 服务间断言签发器（GL-P0-ASSERT-001 keyring 模式）。
 *
 * <p>marketplace 现签 {@code callerKind="service"} + {@code principal="marketplace"} 断言，
 * 按 targetAudience 选对应签名钥（如 grassland-finance），带 org 上下文，用于跨服务调。
 *
 * <p>每请求（每次 finance 调用）现签：规避用户断言 60s TTL 在 Temporal 重试链路下过期的风险（TTL 取 30s）。
 * finance 端 {@code FinanceCallerResolver.authorizeForOrg} 验签 + 校验 principal/org。
 * 服务断言的 {@code isMerchant()} 恒为 false（callerKind=service），不可冒充终端商家用户。
 */
@Component
public class ServiceAssertionIssuer {

    /** 本服务 principal（finance 仅信任 marketplace 编排 AcceptApplicationReservationWorkflow）。 */
    public static final String PRINCIPAL = "marketplace";

    private static final long TTL_SECONDS = 30;

    private final IdentityAssertionSigner signer;
    private final String issuer;

    public ServiceAssertionIssuer(IdentityAssertionSigner signer,
                                  @Value("${identity-assertion.issuer:marketplace}") String issuer) {
        this.signer = signer;
        this.issuer = issuer;
    }

    /**
     * 现签一个带 org 上下文的 marketplace 服务断言（按目标受众选钥）。
     *
     * @param organizationId 组织 ID（可选，用于 org 级授权）
     * @param targetAudience 目标受众服务名（如 grassland-finance）
     * @return 已签 token
     */
    public String issueForOrg(String organizationId, String targetAudience) {
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
