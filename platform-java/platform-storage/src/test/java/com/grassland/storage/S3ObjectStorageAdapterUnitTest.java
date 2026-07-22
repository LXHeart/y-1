package com.grassland.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/** 用 Mockito 验证 S3ObjectStorageAdapter 与 SDK 的交互，不连真实存储。 */
@ExtendWith(MockitoExtension.class)
class S3ObjectStorageAdapterUnitTest {

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner s3Presigner;

    private S3ObjectStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        var props = new ObjectStorageProperties(
                true, URI.create("http://minio:9000"), URI.create("http://localhost:9000"),
                "us-east-1", "ak", "sk", "grassland", true, true);
        adapter = new S3ObjectStorageAdapter(props, s3Client, s3Presigner);
    }

    @Test
    void presignUpload_returnsTicketWithKeyUrlAndHeaders() throws Exception {
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create("http://localhost:9000/grassland/k").toURL());
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);

        UploadTicket ticket = adapter.presignUpload(new PresignRequest("k", "image/png", 60, Map.of()));

        assertThat(ticket.objectKey()).isEqualTo("k");
        assertThat(ticket.method()).isEqualTo("PUT");
        assertThat(ticket.uploadUrl().toString()).contains("grassland", "k");
        assertThat(ticket.headers()).containsEntry("Content-Type", "image/png");
    }

    @Test
    void headObject_missing_returnsEmpty() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.class);
        assertThat(adapter.headObject("missing")).isEmpty();
    }

    @Test
    void headObject_present_returnsMetadata() {
        HeadObjectResponse resp = mock(HeadObjectResponse.class);
        when(resp.contentLength()).thenReturn(123L);
        when(resp.contentType()).thenReturn("image/png");
        when(resp.eTag()).thenReturn("etag1");
        when(resp.lastModified()).thenReturn(Instant.EPOCH);
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(resp);

        Optional<StoredObject> result = adapter.headObject("k");
        assertThat(result).isPresent();
        assertThat(result.get().contentLength()).isEqualTo(123L);
        assertThat(result.get().contentType()).isEqualTo("image/png");
    }

    @Test
    void putObject_forwardsToClient() {
        adapter.putObject("k", new byte[] {1, 2}, "image/png");
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void deleteObject_forwardsToClient() {
        adapter.deleteObject("k");
        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void createBucket_whenMissing_creates() {
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build());
        adapter.createBucketIfNeeded();
        verify(s3Client).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void createBucket_whenExists_doesNotCreate() {
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
                .thenReturn(mock(HeadBucketResponse.class));
        adapter.createBucketIfNeeded();
        verify(s3Client, never()).createBucket(any(CreateBucketRequest.class));
    }
}
