package com.grassland.identity.kyb;

import com.grassland.identity.auth.IdentityException;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** KYB 证据集合校验；提交和批准共用同一套实时媒体检查。 */
@Service
public class KybEvidenceService {

    private final KybMediaClient mediaClient;

    public KybEvidenceService(KybMediaClient mediaClient) {
        this.mediaClient = mediaClient;
    }

    public Mono<Void> requireCurrent(String organizationId, List<MerchantAttachment> attachments) {
        List<MerchantAttachment> documents = attachments.stream()
                .filter(item -> MerchantAttachmentType.fromDb(item.attachmentType()).isDocumentType())
                .toList();
        KybSubmissionService.requireDocuments(documents.stream()
                .map(MerchantAttachment::attachmentType).toList());
        if (new HashSet<>(documents.stream().map(MerchantAttachment::mediaReferenceId).toList()).size()
                != documents.size()) {
            return Mono.error(new IdentityException(409, "同一媒体不能重复作为多种审核证件"));
        }
        return Flux.fromIterable(attachments)
                .flatMap(item -> mediaClient.requireUsable(
                        item.mediaReferenceId(), organizationId, item.uploadedByAccountId()), 4)
                .then();
    }
}
