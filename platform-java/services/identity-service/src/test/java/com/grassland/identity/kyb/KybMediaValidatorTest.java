package com.grassland.identity.kyb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.identity.auth.IdentityException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KybMediaValidatorTest {

    private static final UUID MEDIA_ID = UUID.randomUUID();
    private static final String ACCOUNT_ID = UUID.randomUUID().toString();
    private static final String ORGANIZATION_ID = UUID.randomUUID().toString();

    private final KybMediaValidator validator = new KybMediaValidator();

    @Test
    void acceptsOnlyActiveKybMediaBoundToCallerAndOrganization() {
        KybMediaMetadata metadata = validMetadata();

        assertThat(validator.requireUsable(metadata, ACCOUNT_ID, ORGANIZATION_ID)).isSameAs(metadata);
    }

    @Test
    void rejectsForeignOwnerOrOrganization() {
        assertRejected(copy(validMetadata(), UUID.randomUUID().toString(), ORGANIZATION_ID,
                "merchant_kyb", "active", "merchant_kyb", ORGANIZATION_ID, null));
        assertRejected(copy(validMetadata(), ACCOUNT_ID, UUID.randomUUID().toString(),
                "merchant_kyb", "active", "merchant_kyb", ORGANIZATION_ID, null));
    }

    @Test
    void rejectsWrongPurposeDomainStatusOrExpiredMedia() {
        assertRejected(copy(validMetadata(), ACCOUNT_ID, ORGANIZATION_ID,
                "user_upload", "active", "merchant_kyb", ORGANIZATION_ID, null));
        assertRejected(copy(validMetadata(), ACCOUNT_ID, ORGANIZATION_ID,
                "merchant_kyb", "pending", "merchant_kyb", ORGANIZATION_ID, null));
        assertRejected(copy(validMetadata(), ACCOUNT_ID, ORGANIZATION_ID,
                "merchant_kyb", "active", "application", ORGANIZATION_ID, null));
        assertRejected(copy(validMetadata(), ACCOUNT_ID, ORGANIZATION_ID,
                "merchant_kyb", "active", "merchant_kyb", UUID.randomUUID().toString(), null));
        assertRejected(copy(validMetadata(), ACCOUNT_ID, ORGANIZATION_ID,
                "merchant_kyb", "active", "merchant_kyb", ORGANIZATION_ID, Instant.now().minusSeconds(1)));
    }

    @Test
    void rejectsMediaThatIsNotAnImageOrPdfDocument() {
        for (String mime : java.util.List.of("video/mp4", "audio/mpeg", "text/csv", "image/gif")) {
            assertRejected(new KybMediaMetadata(MEDIA_ID, ACCOUNT_ID, ORGANIZATION_ID, "merchant_kyb",
                    "merchant_kyb", ORGANIZATION_ID, "active", mime, 4096L, null));
        }
    }

    private void assertRejected(KybMediaMetadata metadata) {
        assertThatThrownBy(() -> validator.requireUsable(metadata, ACCOUNT_ID, ORGANIZATION_ID))
                .isInstanceOf(IdentityException.class)
                .satisfies(error -> assertThat(((IdentityException) error).status()).isEqualTo(400));
    }

    private static KybMediaMetadata validMetadata() {
        return new KybMediaMetadata(MEDIA_ID, ACCOUNT_ID, ORGANIZATION_ID, "merchant_kyb",
                "merchant_kyb", ORGANIZATION_ID, "active", "image/png", 4096L, null);
    }

    private static KybMediaMetadata copy(KybMediaMetadata source, String owner, String organization,
                                         String purpose, String status, String domainType,
                                         String domainId, Instant expiresAt) {
        return new KybMediaMetadata(source.id(), owner, organization, purpose, domainType, domainId,
                status, source.mimeType(), source.sizeBytes(), expiresAt);
    }
}
