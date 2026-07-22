package com.grassland.storage;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对象存储配置。属性名 {@code object-storage.*}，经服务 application.yml 的 {@code ${MINIO_*:default}} 映射，
 * 与供应商环境变量解耦（切换真 S3/阿里云 OSS 只改 env，不改库）。
 *
 * <p>仅当 {@code enabled=true} 时强校验必要字段（仿 EdgeRoutingProperties 的 compact-constructor fail-fast）。
 */
@ConfigurationProperties(prefix = "object-storage")
public record ObjectStorageProperties(
        boolean enabled,
        URI endpoint,
        URI publicBaseUrl,
        String region,
        String accessKey,
        String secretKey,
        String bucket,
        boolean pathStyleAccess,
        boolean autoCreateBucket) {

    public ObjectStorageProperties {
        if (region == null || region.isBlank()) {
            region = "us-east-1";
        }
        if (enabled) {
            validateHttpUri(endpoint, "object-storage.endpoint");
            validateHttpUri(publicBaseUrl, "object-storage.public-base-url");
            if (accessKey == null || accessKey.isBlank()) {
                throw new IllegalArgumentException(
                        "object-storage.access-key must be set when object-storage.enabled=true");
            }
            if (secretKey == null || secretKey.isBlank()) {
                throw new IllegalArgumentException(
                        "object-storage.secret-key must be set when object-storage.enabled=true");
            }
            if (bucket == null || bucket.isBlank()) {
                throw new IllegalArgumentException(
                        "object-storage.bucket must be set when object-storage.enabled=true");
            }
        }
    }

    private static void validateHttpUri(URI uri, String name) {
        if (uri == null || uri.getHost() == null) {
            throw new IllegalArgumentException(
                    name + " must be set with a host when object-storage.enabled=true");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(name + " must use http or https: " + uri);
        }
    }
}
