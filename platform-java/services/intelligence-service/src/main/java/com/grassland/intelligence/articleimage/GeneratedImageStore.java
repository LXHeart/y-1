package com.grassland.intelligence.articleimage;

import reactor.core.publisher.Mono;

/**
 * 生成图片暂存端口（草场 intelligence Slice 5 → Slice 8 迁对象存储）。
 *
 * <p>外部契约 {@code GET /api/article-generation/generated-images/{id}} 不变：{@link #store(String)}
 * 返回的 {@link StoredRef#id()} 即 URL 里的 UUID；{@link #find(String)} 返回字节供控制器按 {@code image/png} 原样回写。
 * {@link StoredRef#objectKey()} 是内部 S3/local key，供 media_reference 登记元数据（不作外部授权凭据）。
 *
 * <p>实现二选一（互斥，按 {@code object-storage.enabled} 切换）：
 * <ul>
 *   <li>{@link S3GeneratedImageStore}（生产，MinIO/S3）—— 多副本共享。</li>
 *   <li>{@link LocalGeneratedImageStore}（本地卷，测试/本地开发兜底）—— 单实例。</li>
 * </ul>
 */
public interface GeneratedImageStore {

    /** 暂存一张 base64 PNG，返回 id（URL 用）与对象 key（media 元数据用）。 */
    Mono<StoredRef> store(String base64);

    /** 按 id 取回图片字节；不存在/已过期返回 empty。 */
    Mono<StoredImage> find(String id);

    /**
     * store 结果：id 为 URL 用的 UUID；objectKey 为内部 S3/local key（不作外部授权凭据）；
     * managed=false 表示本地临时兜底，不登记持久 media_reference。
     */
    record StoredRef(String id, String objectKey, boolean managed) {
        StoredRef(String id, String objectKey) {
            this(id, objectKey, true);
        }
    }

    /** 已暂存的图片字节（控制器按 image/png 原样回写）。 */
    record StoredImage(byte[] bytes) {}
}
