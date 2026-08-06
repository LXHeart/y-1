package com.grassland.marketplace.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.taskcatalog.EngagementSubmission;
import com.grassland.marketplace.taskcatalog.EngagementVerificationRepository;
import com.grassland.marketplace.taskcatalog.SubmissionRepository;
import com.grassland.marketplace.taskcatalog.SubmissionStatus;
import com.grassland.marketplace.workflow.saga.DbVerificationChecker;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class DbVerificationCheckerTest {

    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private final EngagementVerificationRepository verifications = mock(EngagementVerificationRepository.class);
    private final DbVerificationChecker checker = new DbVerificationChecker(submissions, verifications);

    @Test
    void failedOverrideBlocksSettlementEvenWhenAutomaticCheckWasInconclusive() {
        var submission = mock(EngagementSubmission.class);
        when(submission.id()).thenReturn("submission-1");
        when(submission.status()).thenReturn(SubmissionStatus.ACCEPTED.dbValue());
        when(submissions.findByApplication("app-1")).thenReturn(Flux.just(submission));
        when(verifications.findEffectiveStatus("submission-1")).thenReturn(Mono.just("failed"));

        assertThat(checker.blocksSettlement("org-1", "app-1")).isTrue();
    }

    @Test
    void passedOverrideDoesNotBlockSettlement() {
        var submission = mock(EngagementSubmission.class);
        when(submission.id()).thenReturn("submission-2");
        when(submission.status()).thenReturn(SubmissionStatus.ACCEPTED.dbValue());
        when(submissions.findByApplication("app-2")).thenReturn(Flux.just(submission));
        when(verifications.findEffectiveStatus("submission-2")).thenReturn(Mono.just("passed"));

        assertThat(checker.blocksSettlement("org-1", "app-2")).isFalse();
    }

    @Test
    void missingEffectiveVerificationDoesNotBlockSettlement() {
        var submission = mock(EngagementSubmission.class);
        when(submission.id()).thenReturn("submission-3");
        when(submission.status()).thenReturn(SubmissionStatus.ACCEPTED.dbValue());
        when(submissions.findByApplication("app-3")).thenReturn(Flux.just(submission));
        when(verifications.findEffectiveStatus("submission-3")).thenReturn(Mono.empty());

        assertThat(checker.blocksSettlement("org-1", "app-3")).isFalse();
    }
}
