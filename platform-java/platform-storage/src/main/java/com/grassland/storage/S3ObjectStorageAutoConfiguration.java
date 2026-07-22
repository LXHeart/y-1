package com.grassland.storage;

import java.net.URI;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 对象存储自动配置。消费者加 {@code implementation(project(":platform-storage"))} 并设
 * {@code object-storage.enabled=true} 即获得 {@link ObjectStorageAdapter} Bean，无需改主类。
 *
 * <p>按服务 opt-in（仿 R2dbcConnectionFactoryConfig 的 @ConditionalOnProperty）：
 * 只引入依赖但未启用的服务不会在启动时 fail。
 *
 * <p>双端点签名（HLD 风险点 #1）：{@link S3Client} 用内部 {@code endpoint}（容器内 http://minio:9000）；
 * {@link S3Presigner} 用 {@code public-base-url}（浏览器可达），未设时回落到 endpoint。
 */
@AutoConfiguration
@ConditionalOnClass(S3Client.class)
@ConditionalOnProperty(prefix = "object-storage", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ObjectStorageProperties.class)
public class S3ObjectStorageAutoConfiguration {

    @Bean(destroyMethod = "close")
    S3Client s3Client(ObjectStorageProperties props) {
        return S3Client.builder()
                .region(Region.of(props.region()))
                .endpointOverride(props.endpoint())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.accessKey(), props.secretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(props.pathStyleAccess())
                        .build())
                .build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(ObjectStorageProperties props) {
        URI presignerEndpoint =
                props.publicBaseUrl() != null ? props.publicBaseUrl() : props.endpoint();
        return S3Presigner.builder()
                .region(Region.of(props.region()))
                .endpointOverride(presignerEndpoint)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(props.pathStyleAccess())
                        .build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.accessKey(), props.secretKey())))
                .build();
    }

    @Bean
    ObjectStorageAdapter objectStorageAdapter(
            ObjectStorageProperties props, S3Client s3Client, S3Presigner s3Presigner) {
        return new S3ObjectStorageAdapter(props, s3Client, s3Presigner);
    }
}
