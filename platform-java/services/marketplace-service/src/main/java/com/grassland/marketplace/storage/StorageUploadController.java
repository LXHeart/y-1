package com.grassland.marketplace.storage;

import com.grassland.storage.ObjectStorageAdapter;
import com.grassland.storage.PresignRequest;
import com.grassland.storage.StoredObject;
import com.grassland.storage.UploadTicket;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * marketplace 首发 consumer：落地 HLD 三步上传流。
 *
 * <ul>
 *   <li>POST /storage/uploads — 申请上传凭据（presigned PUT URL）。presign 无 I/O，事件循环线程直接签名。</li>
 *   <li>POST /storage/uploads/confirm — 确认上传（headObject 校验对象已存在）。</li>
 * </ul>
 *
 * <p>本轮不持久化对象元数据（领域落库留待后续），confirm 仅校验对象已存在于存储。
 */
@RestController
@RequestMapping("/storage")
@ConditionalOnProperty(prefix = "object-storage", name = "enabled", havingValue = "true")
public class StorageUploadController {

    private static final long DEFAULT_EXPIRES_SECONDS = 900;

    private final ObjectStorageAdapter objectStorage;

    public StorageUploadController(ObjectStorageAdapter objectStorage) {
        this.objectStorage = objectStorage;
    }

    @PostMapping("/uploads")
    public Mono<UploadTicket> createUpload(@RequestBody CreateUploadRequest request) {
        String key = request.scope() + "/" + UUID.randomUUID();
        UploadTicket ticket = objectStorage.presignUpload(
                new PresignRequest(key, request.contentType(), DEFAULT_EXPIRES_SECONDS, Map.of()));
        return Mono.just(ticket);
    }

    @PostMapping("/uploads/confirm")
    public Mono<StoredObject> confirmUpload(@RequestBody Map<String, String> body) {
        String key = body.get("objectKey");
        if (key == null || key.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "objectKey is required"));
        }
        return Mono.fromCallable(() -> objectStorage.headObject(key))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(opt -> opt.<Mono<StoredObject>>map(Mono::just)
                        .orElseGet(() -> Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "object not found: " + key))));
    }
}
