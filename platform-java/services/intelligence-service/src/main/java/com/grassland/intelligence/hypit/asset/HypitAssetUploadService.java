package com.grassland.intelligence.hypit.asset;

import com.grassland.intelligence.hypit.asset.HypitResourceService.IngestReceipt;
import com.grassland.intelligence.security.IntelligenceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import com.grassland.storage.ObjectStorageAdapter;

/**
 * 素材字节接收面（C107F2-31 / §6.14）：256MiB 上限 + 真实字节复制。
 *
 * <p>
 * 上传（multipart）：先按声明长度早拒（不移动字节），回执 sizeBytes 超限同样拒收——两种路径都不出现 ready 素材；探测核验在
 * asset job 流程内（{@link HypitAssetService}）。素材库交接（mediaId/sourceContext）：
 * 从对象存储读源字节流式转发给 sidecar 资源面，产出 broker 可消费的 {@code res-} 句柄——不得只落
 * {@code media:} JSON 引用（旧缺陷：句柄无字节，probe/分析全部落空）。
 */
@Service
public class HypitAssetUploadService {

	/** §6.14：单素材上传/交接上限 256MiB（低于资源面通用 500MiB 物理上限）。 */
	static final long MAX_ASSET_UPLOAD_BYTES = 256L * 1024 * 1024;

	private final HypitResourceService resources;
	private final ObjectProvider<ObjectStorageAdapter> storageProvider;

	public HypitAssetUploadService(HypitResourceService resources,
			ObjectProvider<ObjectStorageAdapter> storageProvider) {
		this.resources = resources;
		this.storageProvider = storageProvider;
	}

	private static IntelligenceException tooLarge(long sizeBytes) {
		return new IntelligenceException(413, "hypit_too_large", "素材超过 256MiB 上限（" + sizeBytes + " 字节），已停止接收。");
	}

	/**
	 * multipart 上传接收：声明长度超限在入口拒绝（零字节移动）；长度未知时由资源面边收边限， 回执超限在此拒收（无 ready
	 * 素材，孤儿字节由生命周期卡清理）。
	 */
	public Mono<IngestReceipt> ingestUpload(FilePart file, String fileName, String contentType) {
		long declared = file.headers().getContentLength();
		if (declared > MAX_ASSET_UPLOAD_BYTES) {
			return Mono.error(tooLarge(declared));
		}
		return resources.ingest(file.content(), fileName, contentType, declared)
				.flatMap(receipt -> receipt.sizeBytes() > MAX_ASSET_UPLOAD_BYTES
						? Mono.<IngestReceipt>error(tooLarge(receipt.sizeBytes()))
						: Mono.just(receipt));
	}

	/** 上传后按 (project, sha256) 找既有 ready 素材：相同字节复用资源，不建第二行。 */
	public static boolean exceedsCap(long sizeBytes) {
		return sizeBytes > MAX_ASSET_UPLOAD_BYTES;
	}

	/**
	 * 素材库媒体复制：对象存储源字节 → sidecar 资源面流式转发。源 size 超限先拒（不读对象）； 对象存储未配置时如实
	 * 503（可行动错误，不落半成品）。
	 */
	public Mono<IngestReceipt> copyMediaObject(String objectKey, String mimeType, long sizeBytes) {
		if (exceedsCap(sizeBytes)) {
			return Mono.error(tooLarge(sizeBytes));
		}
		ObjectStorageAdapter storage = storageProvider.getIfAvailable();
		if (storage == null) {
			return Mono.error(new IntelligenceException(503, "hypit_backend_unavailable", "对象存储未配置，无法复制素材库媒体字节。"));
		}
		String fileName = objectKey.contains("/") ? objectKey.substring(objectKey.lastIndexOf('/') + 1) : objectKey;
		return Mono.fromCallable(() -> storage.getObject(objectKey)).subscribeOn(Schedulers.boundedElastic())
				.mapNotNull(bytes -> (DataBuffer) DefaultDataBufferFactory.sharedInstance.wrap(bytes))
				.flatMap(buffer -> resources.ingest(Flux.just(buffer), fileName, mimeType, buffer.readableByteCount()))
				.flatMap(receipt -> exceedsCap(receipt.sizeBytes())
						? Mono.<IngestReceipt>error(tooLarge(receipt.sizeBytes()))
						: Mono.just(receipt));
	}
}
