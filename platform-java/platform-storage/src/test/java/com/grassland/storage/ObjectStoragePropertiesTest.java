package com.grassland.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

/** 验证 ObjectStorageProperties 的 compact-constructor 校验与默认值。 */
class ObjectStoragePropertiesTest {

    @Test
    void disabled_doesNotValidateRequiredFields() {
        var props = new ObjectStorageProperties(false, null, null, null, null, null, null, true, true);
        assertThat(props.enabled()).isFalse();
        assertThat(props.region()).isEqualTo("us-east-1");
    }

    @Test
    void enabled_appliesRegionDefault() {
        var props = validProps();
        assertThat(props.region()).isEqualTo("us-east-1");
        assertThat(props.bucket()).isEqualTo("grassland");
    }

    @Test
    void enabled_missingEndpoint_throws() {
        assertThatThrownBy(() -> new ObjectStorageProperties(
                true, null, URI.create("http://localhost:9000"), null, "ak", "sk", "b", true, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("object-storage.endpoint");
    }

    @Test
    void enabled_missingPublicBaseUrl_throws() {
        assertThatThrownBy(() -> new ObjectStorageProperties(
                true, URI.create("http://minio:9000"), null, null, "ak", "sk", "b", true, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("object-storage.public-base-url");
    }

    @Test
    void enabled_missingCredentials_throws() {
        assertThatThrownBy(() -> new ObjectStorageProperties(
                true, URI.create("http://minio:9000"), URI.create("http://localhost:9000"),
                null, "", "sk", "b", true, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("access-key");
    }

    @Test
    void enabled_missingBucket_throws() {
        assertThatThrownBy(() -> new ObjectStorageProperties(
                true, URI.create("http://minio:9000"), URI.create("http://localhost:9000"),
                null, "ak", "sk", " ", true, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("object-storage.bucket");
    }

    @Test
    void enabled_invalidScheme_throws() {
        assertThatThrownBy(() -> new ObjectStorageProperties(
                true, URI.create("ftp://minio"), URI.create("http://localhost:9000"),
                null, "ak", "sk", "b", true, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("http or https");
    }

    private static ObjectStorageProperties validProps() {
        return new ObjectStorageProperties(
                true, URI.create("http://minio:9000"), URI.create("http://localhost:9000"),
                null, "ak", "sk", "grassland", true, true);
    }
}