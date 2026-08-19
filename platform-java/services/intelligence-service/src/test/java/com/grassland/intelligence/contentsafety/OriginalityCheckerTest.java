package com.grassland.intelligence.contentsafety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.contentsafety.ContentFingerprintRepository.Fingerprint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class OriginalityCheckerTest {

    private static final String ORIGINAL =
            "今天去了一家新开的咖啡店，拿铁奶泡绵密，环境安静适合办公，店员会介绍豆子风味。";
    private static final String SMALL_EDIT =
            "今天去了一家新开的咖啡店，拿铁奶泡很绵密，环境安静适合办公，店员会介绍咖啡豆风味。";
    private static final String DIFFERENT =
            "夏季露营需要检查天气，准备防晒用品和应急药品，路线应提前告知家人。";

    @Mock
    private ContentFingerprintRepository repository;

    private OriginalityChecker checker;

    @BeforeEach
    void setUp() {
        checker = new OriginalityChecker(repository);
    }

    @Test
    void simHashIsStableAndSeparatesSmallEditsFromDifferentText() {
        var original = OriginalityChecker.fingerprint(ORIGINAL);
        var repeated = OriginalityChecker.fingerprint(ORIGINAL);
        var edited = OriginalityChecker.fingerprint(SMALL_EDIT);
        var different = OriginalityChecker.fingerprint(DIFFERENT);

        assertThat(repeated.simhash()).isEqualTo(original.simhash());
        assertThat(OriginalityChecker.hammingDistance(original.simhash(), edited.simhash()))
                .isLessThanOrEqualTo(16);
        assertThat(OriginalityChecker.hammingDistance(original.simhash(), different.simhash()))
                .isGreaterThan(16);
    }

    @Test
    void repeatedBigramsProduceLowOriginalityFinding() {
        when(repository.findCandidates(eq("owner"), isNull(), any())).thenReturn(Flux.empty());
        when(repository.insert(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        List<SafetyReport.Finding> findings = checker.checkAndRecord(
                "哈哈哈哈哈哈哈哈哈哈哈哈", context("owner", null)).block();

        assertThat(findings).anyMatch(finding -> finding.category().equals("low_originality"));
    }

    @Test
    void duplicateFindingContainsMetadataButNeverSourceText() {
        long hash = OriginalityChecker.fingerprint(ORIGINAL).simhash();
        Fingerprint existing = new Fingerprint(
                UUID.randomUUID(), "other-owner", "task-1", "application-1",
                "douyin", "video", hash, 40, "generation",
                Instant.parse("2026-08-18T10:00:00Z"));
        when(repository.findCandidates(eq("owner"), eq("task-1"), any()))
                .thenReturn(Flux.just(existing));
        when(repository.insert(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        List<SafetyReport.Finding> findings = checker.checkAndRecord(
                ORIGINAL, context("owner", "task-1")).block();

        SafetyReport.Finding duplicate = findings.stream()
                .filter(finding -> finding.category().equals("duplicate_content"))
                .findFirst().orElseThrow();
        assertThat(duplicate.match()).contains("100%", "douyin", "2026-08-18");
        assertThat(duplicate.match()).doesNotContain(ORIGINAL);
        assertThat(duplicate.advice()).doesNotContain(ORIGINAL);
    }

    @Test
    void repositoryInsertFailureDoesNotInterruptSafetyResult() {
        when(repository.findCandidates(eq("owner"), isNull(), any())).thenReturn(Flux.empty());
        when(repository.insert(any())).thenReturn(Mono.error(new RuntimeException("db down")));

        StepVerifier.create(checker.checkAndRecord(ORIGINAL, context("owner", null)))
                .expectNext(List.of())
                .verifyComplete();
        verify(repository).insert(any());
    }

    private static OriginalityChecker.Context context(String owner, String task) {
        return new OriginalityChecker.Context(
                owner, task, "application-1", "douyin", "video", "generation");
    }
}
