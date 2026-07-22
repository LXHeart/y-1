package com.grassland.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
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
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** 用 testcontainers 起 MinIO，端到端验证 put/head/get/delete 与 presigned 上传。 */
@Testcontainers
class MinIoObjectStorageAdapterIT {

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>("minio/minio:latest")
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    private static S3ObjectStorageAdapter newAdapter() {
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
        S3Presigner presigner = S3Presigner.builder()
                .region(Region.of(props.region()))
                .endpointOverride(props.endpoint())
                .serviceConfiguration(serviceCfg)
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .build();
        return new S3ObjectStorageAdapter(props, s3, presigner);
    }

    @Test
    void putHeadGetDelete_roundTrip() {
        var adapter = newAdapter();
        adapter.createBucketIfNeeded();
        String key = "it/" + UUID.randomUUID();

        adapter.putObject(key, "hello".getBytes(), "text/plain");

        var head = adapter.headObject(key);
        assertThat(head).isPresent();
        assertThat(head.get().contentType()).isEqualTo("text/plain");
        assertThat(head.get().contentLength()).isEqualTo(5L);

        assertThat(new String(adapter.getObject(key))).isEqualTo("hello");

        adapter.deleteObject(key);
        assertThat(adapter.headObject(key)).isEmpty();
    }

    @Test
    void presignUpload_canBeUsedByHttpClient() throws Exception {
        var adapter = newAdapter();
        adapter.createBucketIfNeeded();
        String key = "presign/" + UUID.randomUUID();

        UploadTicket ticket = adapter.presignUpload(new PresignRequest(key, "text/plain", 60, Map.of()));
        assertThat(ticket.method()).isEqualTo("PUT");

        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<Void> resp = http.send(
                HttpRequest.newBuilder(ticket.uploadUrl())
                        .header("Content-Type", "text/plain")
                        .PUT(HttpRequest.BodyPublishers.ofString("presigned-body"))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(resp.statusCode()).isEqualTo(200);

        assertThat(new String(adapter.getObject(key))).isEqualTo("presigned-body");
    }
}
