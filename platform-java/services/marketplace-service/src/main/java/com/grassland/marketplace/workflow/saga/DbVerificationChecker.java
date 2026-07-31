package com.grassland.marketplace.workflow.saga;

import com.grassland.marketplace.taskcatalog.EngagementVerificationRepository;
import com.grassland.marketplace.taskcatalog.SubmissionRepository;
import com.grassland.marketplace.taskcatalog.SubmissionStatus;
import org.springframework.stereotype.Component;

/**
 * 真 {@link VerificationChecker}（Verification v1）：按 app → 已确认（accepted）的 submission → 其核验记录，
 * {@code status='failed'} 即阻断。镜像 {@link HttpDisputeChecker}（activity 线程内 {@code block()}）。
 *
 * <p>resolve 路径：{@code engagementRef}=applicationId → 该 app 的 accepted submission（confirm 把 pending→accepted，
 * 故 accepted 即「被确认的那份交付物」，取最新一条）→ 其核验记录 failed？无 accepted submission / 无核验记录 → 不阻断。
 */
@Component
public class DbVerificationChecker implements VerificationChecker {

    private final SubmissionRepository submissions;
    private final EngagementVerificationRepository verifications;

    public DbVerificationChecker(SubmissionRepository submissions, EngagementVerificationRepository verifications) {
        this.submissions = submissions;
        this.verifications = verifications;
    }

    @Override
    public boolean blocksSettlement(String organizationId, String engagementRef) {
        return Boolean.TRUE.equals(
                submissions.findByApplication(engagementRef)
                        .filter(s -> SubmissionStatus.ACCEPTED.dbValue().equalsIgnoreCase(s.status()))
                        .next()  // 最新 accepted submission（findByApplication 已按 created_at DESC）
                        .flatMap(s -> verifications.findBySubmission(s.id())
                                .map(v -> "failed".equalsIgnoreCase(v.status()))
                                .defaultIfEmpty(false))  // 无核验记录 → 不阻断
                        .defaultIfEmpty(false)  // 无 accepted submission → 不阻断
                        .block());
    }
}
