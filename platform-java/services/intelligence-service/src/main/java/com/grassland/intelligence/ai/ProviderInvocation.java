package com.grassland.intelligence.ai;

/** Runtime-only provider credentials and routing data. Never serialize or log the bearer value. */
public record ProviderInvocation(
        String provider,
        String baseUrl,
        String model,
        String bearer,
        boolean byok) {

    public ProviderInvocation {
        requireText(provider, "provider");
        requireText(baseUrl, "baseUrl");
        requireText(model, "model");
        requireText(bearer, "bearer");
    }

    @Override
    public String toString() {
        return "ProviderInvocation[provider=" + provider
                + ", baseUrl=" + baseUrl
                + ", model=" + model
                + ", bearer=[REDACTED], byok=" + byok + "]";
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
