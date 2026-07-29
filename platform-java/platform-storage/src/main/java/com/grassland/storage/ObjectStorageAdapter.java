package com.grassland.storage;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * 对象存储平台端口（HLD 12.1 ObjectStorageAdapter）。
 * 实现应保证：服务端操作走内部 endpoint；presigned 走浏览器可达的 public-base-url。
 */
public interface ObjectStorageAdapter {

    /** 申请上传凭据（presigned PUT URL）。无 I/O，仅签名。 */
    UploadTicket presignUpload(PresignRequest request);

    /** 申请下载 URL（presigned GET）。无 I/O，仅签名。 */
    URI presignDownload(String key, long expiresSeconds);

    /** 服务端直接上传对象内容。阻塞 I/O，调用方需自行离线程。 */
    void putObject(String key, byte[] content, String contentType);

    /** 读取对象内容字节。对象不存在时抛 {@code NoSuchKeyException}。 */
    byte[] getObject(String key);

    /** 查询对象元数据；不存在返回 {@link Optional#empty()}。 */
    Optional<StoredObject> headObject(String key);

    /** 删除对象（幂等）。 */
    void deleteObject(String key);

    /**
     * 列出给定前缀下的对象元数据（best-effort 单页，最多约 1000 个 key）。
     *
     * <p>用于 TTL/孤儿对象清理等后台巡检；返回的 {@link StoredObject#contentType()} 在 list 响应中
     * 不可用，恒为 {@code null}（需 content-type 请另调 {@link #headObject(String)}）。调用方需自行离线程。
     */
    List<StoredObject> listObjects(String prefix);
}
