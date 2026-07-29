package com.grassland.intelligence.articleimage;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.storage.ObjectStorageProperties;
import com.grassland.storage.S3ObjectStorageAdapter;
import java.net.URI;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** 用 testcontainers MinIO 端到端验证 S3GeneratedImageStore 的 store→find 往返与未知 id 行为。 */
@Testcontainers
class S3GeneratedImageStoreIT {

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>("minio/minio:latest")
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    private static S3GeneratedImageStore newStore() {
        String url = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        var props = new ObjectStorageProperties(
                true, URI.create(url), URI.create(url), "us-east-1",
                "minioadmin", "minioadmin", "grassland-it", true, true);
        var creds = AwsBasicCredentials.create(props.accessKey(), props.secretKey());
        var serviceCfg = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        S3Client s3 = S3Client.builder()
                .region(Region.of(props.region()))
                .endpointOverride(props.endpoint())
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .serviceConfiguration(serviceCfg)
                .build();
        // 直接建 bucket（@PostConstruct createBucketIfNeeded 是包级可见，跨包无法调用）。
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(props.bucket()).build());
        } catch (BucketAlreadyOwnedByYouException ignored) {
            // 已存在
        }
        var presigner = S3Presigner.builder()
                .region(Region.of(props.region()))
                .endpointOverride(props.endpoint())
                .serviceConfiguration(serviceCfg)
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .build();
        return new S3GeneratedImageStore(new S3ObjectStorageAdapter(props, s3, presigner),
                "it-generated", 1800, Clock.systemUTC());
    }

    @Test
    void storeAndFind_roundTripsExactBytes() {
        S3GeneratedImageStore store = newStore();
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 1, 2, 3};

        GeneratedImageStore.StoredRef ref = store.store(Base64.getEncoder().encodeToString(png)).block();

        assertThat(ref.id()).matches("[0-9a-f-]{36}");
        GeneratedImageStore.StoredImage result = store.find(ref.id()).block();
        assertThat(result).isNotNull();
        assertThat(result.bytes()).isEqualTo(png);
    }

    @Test
    void find_returnsEmptyForUnknownId() {
        S3GeneratedImageStore store = newStore();

        assertThat(store.find(UUID.randomUUID().toString()).block()).isNull();
    }
}
