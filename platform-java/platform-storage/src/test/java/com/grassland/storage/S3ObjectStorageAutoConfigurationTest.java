package com.grassland.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

/** 验证 autoconfigure 的条件装配：enabled 时装配 Bean，未设时不装配。 */
class S3ObjectStorageAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(S3ObjectStorageAutoConfiguration.class));

    @Test
    void enabled_configuresAdapterAndClientBeans() {
        runner.withPropertyValues(
                "object-storage.enabled=true",
                "object-storage.endpoint=http://localhost:9000",
                "object-storage.public-base-url=http://localhost:9000",
                "object-storage.access-key=ak",
                "object-storage.secret-key=sk",
                "object-storage.bucket=grassland",
                "object-storage.auto-create-bucket=false")
            .run(context -> {
                assertThat(context).hasSingleBean(ObjectStorageAdapter.class);
                assertThat(context).hasSingleBean(S3Client.class);
            });
    }

    @Test
    void disabled_doesNotConfigureAdapter() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ObjectStorageAdapter.class));
    }
}
