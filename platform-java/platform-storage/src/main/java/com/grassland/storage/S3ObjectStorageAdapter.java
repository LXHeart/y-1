package com.grassland.storage;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * S3 兼容（MinIO）对象存储适配器实现。
 *
 * <p>服务端操作（put/get/head/delete/bucket）走内部 {@code endpoint}；
 * presigned 走 {@code public-base-url}（浏览器可达）—— 因 SigV4 签 Host 头，presigned 必须签客户端实际访问的地址。
 */
public final class S3ObjectStorageAdapter implements ObjectStorageAdapter {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorageAdapter.class);

    private final ObjectStorageProperties properties;
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    public S3ObjectStorageAdapter(
            ObjectStorageProperties properties, S3Client s3Client, S3Presigner s3Presigner) {
        this.properties = properties;
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
    }

    @PostConstruct
    void createBucketIfNeeded() {
        if (!properties.autoCreateBucket()) {
            return;
        }
        String bucket = properties.bucket();
        if (bucketExists(bucket)) {
            log.debug("Bucket already exists: {}", bucket);
            return;
        }
        try {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("Created bucket: {}", bucket);
        } catch (BucketAlreadyOwnedByYouException e) {
            log.debug("Bucket already owned (concurrent create): {}", bucket);
        }
    }

    private boolean bucketExists(String bucket) {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public UploadTicket presignUpload(PresignRequest request) {
        Duration expires = Duration.ofSeconds(request.expiresSeconds());
        PutObjectRequest.Builder objBuilder = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(request.key());
        if (request.contentType() != null && !request.contentType().isBlank()) {
            objBuilder.contentType(request.contentType());
        }
        if (!request.metadata().isEmpty()) {
            objBuilder.metadata(request.metadata());
        }
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .putObjectRequest(objBuilder.build())
                .signatureDuration(expires)
                .build();
        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);
        Map<String, String> headers = new LinkedHashMap<>();
        if (request.contentType() != null && !request.contentType().isBlank()) {
            headers.put("Content-Type", request.contentType());
        }
        return new UploadTicket(
                request.key(), toUri(presigned.url()), "PUT", headers, Instant.now().plus(expires));
    }

    @Override
    public URI presignDownload(String key, long expiresSeconds) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(key)
                        .build())
                .signatureDuration(Duration.ofSeconds(expiresSeconds))
                .build();
        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);
        return toUri(presigned.url());
    }

    @Override
    public void putObject(String key, byte[] content, String contentType) {
        PutObjectRequest.Builder builder =
                PutObjectRequest.builder().bucket(properties.bucket()).key(key);
        if (contentType != null && !contentType.isBlank()) {
            builder.contentType(contentType);
        }
        s3Client.putObject(builder.build(), RequestBody.fromBytes(content));
    }

    @Override
    public byte[] getObject(String key) {
        return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(key)
                        .build())
                .asByteArray();
    }

    @Override
    public Optional<StoredObject> headObject(String key) {
        try {
            HeadObjectResponse resp = s3Client.headObject(
                    HeadObjectRequest.builder().bucket(properties.bucket()).key(key).build());
            return Optional.of(new StoredObject(
                    key, resp.contentLength(), resp.contentType(), resp.eTag(), resp.lastModified()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public void deleteObject(String key) {
        s3Client.deleteObject(
                DeleteObjectRequest.builder().bucket(properties.bucket()).key(key).build());
    }

    private static URI toUri(URL url) {
        try {
            return url.toURI();
        } catch (URISyntaxException e) {
            throw new ObjectStorageException("Invalid presigned URL: " + url, e);
        }
    }
}
