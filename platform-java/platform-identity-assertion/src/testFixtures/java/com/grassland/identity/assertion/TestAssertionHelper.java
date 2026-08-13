package com.grassland.identity.assertion;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Shared keyring fixtures for service and library tests. */
public final class TestAssertionHelper {

    public static final String DEFAULT_SECRET = "test-secret-32-chars-min!!!!!!";

    public static IdentityAssertionSigner signer(
            String issuer, String purpose, String audience, String secret, Duration leeway) {
        String kid = issuer + "-" + purpose + "-" + audience.replace("grassland-", "") + "-test-v1";
        IdentityAssertionProperties.KeyEntry signing = new IdentityAssertionProperties.KeyEntry(
                kid, issuer, purpose, audience, secret);
        IdentityAssertionProperties.KeyEntry verify = new IdentityAssertionProperties.KeyEntry(
                kid, issuer, purpose, audience, secret);
        IdentityAssertionProperties properties = new IdentityAssertionProperties(
                true, 60, audience, null, leeway == null ? 0 : leeway.toSeconds(), null,
                issuer, List.of(signing), List.of(verify),
                new IdentityAssertionProperties.ReplayProtectionConfig(false));
        return new IdentityAssertionSigner(
                PropertiesKeyring.from(properties), issuer, AssertionReplayGuard.NO_OP, leeway);
    }

    public static IdentityAssertionSigner userSigner(String issuer, String audience) {
        return signer(issuer, "user", audience, DEFAULT_SECRET, Duration.ZERO);
    }

    public static IdentityAssertionSigner serviceSigner(String issuer, String audience) {
        return signer(issuer, "service", audience, DEFAULT_SECRET, Duration.ZERO);
    }

    public static IdentityAssertionSigner edgeBffSigner() {
        return userSigner("edge-bff", "grassland-identity");
    }

    public static IdentityAssertionSigner edgeBffSigner(String audience) {
        return userSigner("edge-bff", audience);
    }

    public static IdentityAssertionSigner marketplaceServiceSigner() {
        return serviceSigner("marketplace", "grassland-finance");
    }

    public static IdentityAssertionSigner trustServiceSigner() {
        return serviceSigner("trust", "grassland-finance");
    }

    /** Register the same per-service key graph used by production YAML in a Spring test context. */
    public static void registerServiceKeyring(DynamicPropertyRegistry registry, String service) {
        String issuer = service;
        List<KeySpec> signing = new ArrayList<>();
        List<KeySpec> verify = new ArrayList<>();
        switch (service) {
            case "edge-bff" -> {
                for (String target : List.of("identity", "marketplace", "finance", "trust", "intelligence")) {
                    signing.add(new KeySpec("edge-bff", "user", "grassland-" + target));
                }
            }
            case "identity" -> {
                signing.add(new KeySpec("identity", "service", "grassland-finance"));
                signing.add(new KeySpec("identity", "service", "grassland-intelligence"));
                verify.add(new KeySpec("edge-bff", "user", "grassland-identity"));
                for (String caller : List.of("trust", "marketplace", "intelligence")) {
                    verify.add(new KeySpec(caller, "service", "grassland-identity"));
                }
            }
            case "marketplace" -> {
                for (String target : List.of("finance", "trust", "intelligence", "identity")) {
                    signing.add(new KeySpec("marketplace", "service", "grassland-" + target));
                }
                verify.add(new KeySpec("edge-bff", "user", "grassland-marketplace"));
                verify.add(new KeySpec("trust", "service", "grassland-marketplace"));
                verify.add(new KeySpec("intelligence", "service", "grassland-marketplace"));
            }
            case "intelligence" -> {
                for (String target : List.of("marketplace", "finance", "identity")) {
                    signing.add(new KeySpec("intelligence", "service", "grassland-" + target));
                }
                verify.add(new KeySpec("edge-bff", "user", "grassland-intelligence"));
                verify.add(new KeySpec("marketplace", "service", "grassland-intelligence"));
                verify.add(new KeySpec("identity", "service", "grassland-intelligence"));
            }
            case "finance" -> {
                verify.add(new KeySpec("edge-bff", "user", "grassland-finance"));
                for (String caller : List.of("marketplace", "trust", "intelligence", "identity")) {
                    verify.add(new KeySpec(caller, "service", "grassland-finance"));
                }
            }
            case "trust" -> {
                for (String target : List.of("finance", "marketplace", "identity")) {
                    signing.add(new KeySpec("trust", "service", "grassland-" + target));
                }
                verify.add(new KeySpec("edge-bff", "user", "grassland-trust"));
                verify.add(new KeySpec("marketplace", "service", "grassland-trust"));
            }
            default -> throw new IllegalArgumentException("Unknown service keyring: " + service);
        }

        Map<String, String> properties = keyringProperties(issuer, signing, verify);
        properties.forEach((name, value) -> registry.add(name, () -> value));
    }

    /** Property strings for annotation-based Spring context tests. */
    public static Map<String, String> keyringProperties(
            String issuer, List<KeySpec> signing, List<KeySpec> verify) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("identity-assertion.enabled", "true");
        result.put("identity-assertion.issuer", issuer);
        addKeys(result, "signing-keys", signing, false);
        addKeys(result, "verify-keys", verify, true);
        return result;
    }

    private static void addKeys(
            Map<String, String> properties, String group, List<KeySpec> keys, boolean includeIssuer) {
        for (int index = 0; index < keys.size(); index++) {
            KeySpec key = keys.get(index);
            String prefix = "identity-assertion." + group + "[" + index + "].";
            properties.put(prefix + "kid", key.kid());
            if (includeIssuer) {
                properties.put(prefix + "issuer", key.issuer());
            }
            properties.put(prefix + "purpose", key.purpose());
            properties.put(prefix + "audience", key.audience());
            properties.put(prefix + "secret", DEFAULT_SECRET);
        }
    }

    public record KeySpec(String issuer, String purpose, String audience) {
        public String kid() {
            return issuer + "-" + purpose + "-" + audience.replace("grassland-", "") + "-test-v1";
        }
    }

    public static String signUserAssertion(
            IdentityAssertionSigner signer, String accountId, String activeIdentityType,
            String organizationId, String targetAudience) {
        Instant now = Instant.now();
        IdentityAssertion assertion = new IdentityAssertion(
                accountId, activeIdentityType, "sid-" + accountId, organizationId, null,
                "cookie-session", "level1", null,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                targetAudience, now, now.plusSeconds(60),
                "user", null, null);
        return signer.sign(assertion, targetAudience);
    }

    public static String signServiceAssertion(
            IdentityAssertionSigner signer, String principal, String organizationId, String targetAudience) {
        Instant now = Instant.now();
        IdentityAssertion assertion = new IdentityAssertion(
                "service:" + principal, null, null, organizationId, null,
                "service", "internal", null,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                targetAudience, now, now.plusSeconds(30),
                "service", principal, null);
        return signer.sign(assertion, targetAudience);
    }

    private TestAssertionHelper() {}
}
