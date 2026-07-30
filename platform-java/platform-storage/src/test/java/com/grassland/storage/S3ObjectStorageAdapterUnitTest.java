package com.grassland.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
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

        UploadTicket ticket = adapter.presignUpload(new PresignRequest("k", "image/png", 60, Map.of(), 42L));

        assertThat(ticket.objectKey()).isEqualTo("k");
        assertThat(ticket.method()).isEqualTo("PUT");
        assertThat(ticket.uploadUrl().toString()).contains("grassland", "k");
        assertThat(ticket.headers()).containsEntry("Content-Type", "image/png");
        verify(s3Presigner).presignPutObject(argThat((PutObjectPresignRequest req) ->
                Long.valueOf(42L).equals(req.putObjectRequest().contentLength())));
    }

    @Test
    void presignDownload_withDisposition_signsResponseContentDisposition() throws Exception {
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create("http://localhost:9000/grassland/k").toURL());
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        URI url = adapter.presignDownload("k", 60, "attachment; filename=\"x.pdf\"");

        assertThat(url.toString()).contains("grassland", "k");
        verify(s3Presigner).presignGetObject(argThat((GetObjectPresignRequest req) ->
                "attachment; filename=\"x.pdf\"".equals(req.getObjectRequest().responseContentDisposition())));
    }

    @Test
    void presignDownload_withoutDisposition_omitsResponseContentDisposition() throws Exception {
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create("http://localhost:9000/grassland/k").toURL());
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        adapter.presignDownload("k", 60, null);

        verify(s3Presigner).presignGetObject(argThat((GetObjectPresignRequest req) -> {
            String d = req.getObjectRequest().responseContentDisposition();
            return d == null || d.isEmpty();
        }));
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
    void listObjects_returnsMetadataForPrefixWithoutContentType() {
        S3Object a = mock(S3Object.class);
        when(a.key()).thenReturn("prefix/a.png");
        when(a.size()).thenReturn(7L);
        when(a.eTag()).thenReturn("etag-a");
        when(a.lastModified()).thenReturn(Instant.EPOCH);
        S3Object b = mock(S3Object.class);
        when(b.key()).thenReturn("prefix/b.png");
        when(b.size()).thenReturn(3L);
        when(b.eTag()).thenReturn("etag-b");
        when(b.lastModified()).thenReturn(Instant.parse("2026-07-29T00:00:00Z"));
        ListObjectsV2Response resp = mock(ListObjectsV2Response.class);
        when(resp.contents()).thenReturn(List.of(a, b));
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(resp);

        List<StoredObject> result = adapter.listObjects("prefix/");

        verify(s3Client).listObjectsV2(argThat((ListObjectsV2Request req) -> "prefix/".equals(req.prefix())));
        assertThat(result).hasSize(2);
        assertThat(result.get(0).key()).isEqualTo("prefix/a.png");
        assertThat(result.get(0).contentLength()).isEqualTo(7L);
        assertThat(result.get(0).contentType()).isNull();
        assertThat(result.get(0).lastModified()).isEqualTo(Instant.EPOCH);
        assertThat(result.get(1).key()).isEqualTo("prefix/b.png");
    }

    @Test
    void listObjects_followsContinuationTokensUntilComplete() {
        S3Object first = mock(S3Object.class);
        when(first.key()).thenReturn("prefix/first.png");
        when(first.size()).thenReturn(7L);
        when(first.eTag()).thenReturn("e1");
        when(first.lastModified()).thenReturn(Instant.EPOCH);
        S3Object second = mock(S3Object.class);
        when(second.key()).thenReturn("prefix/second.png");
        when(second.size()).thenReturn(3L);

        ListObjectsV2Response firstPage = mock(ListObjectsV2Response.class);
        when(firstPage.contents()).thenReturn(List.of(first));
        when(firstPage.isTruncated()).thenReturn(true);
        when(firstPage.nextContinuationToken()).thenReturn("page-2");
        ListObjectsV2Response secondPage = mock(ListObjectsV2Response.class);
        when(secondPage.contents()).thenReturn(List.of(second));
        when(secondPage.isTruncated()).thenReturn(false);
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(firstPage, secondPage);

        List<StoredObject> result = adapter.listObjects("prefix/");

        assertThat(result).extracting(StoredObject::key)
                .containsExactly("prefix/first.png", "prefix/second.png");
        ArgumentCaptor<ListObjectsV2Request> captor = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3Client, times(2)).listObjectsV2(captor.capture());
        assertThat(captor.getAllValues()).extracting(ListObjectsV2Request::continuationToken)
                .containsExactly(null, "page-2");
        assertThat(captor.getAllValues()).allMatch(req -> "prefix/".equals(req.prefix()));
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