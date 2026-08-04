package com.grassland.edge.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("edge.security")
public record EdgeSecurityProperties(
        String csrfOriginCheck,
        List<String> allowedOrigins,
        String internalIdentityHeader) {

    public EdgeSecurityProperties {
        csrfOriginCheck = csrfOriginCheck == null ? "1" : csrfOriginCheck.trim();
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        internalIdentityHeader = internalIdentityHeader == null || internalIdentityHeader.isBlank()
                ? "X-Grassland-Identity" : internalIdentityHeader.trim();
    }

    public boolean csrfEnabled() {
        return !("0".equals(csrfOriginCheck) || "false".equalsIgnoreCase(csrfOriginCheck));
    }
}
