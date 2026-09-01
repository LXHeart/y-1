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

    private ContentSafetyProperties properties;
    private OriginalityChecker checker;

    @BeforeEach
    void setUp() {
        properties = new ContentSafetyProperties();
        checker = new OriginalityChecker(repository, properties);
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
    void duplicateSameApplicationUsesSameSourceCopyAndNeverSourceText() {
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
        // 任务书 #63 4.2 分支1：applicationId 相同 = 本文迭代，同源降噪文案
        assertThat(duplicate.match()).isEqualTo("与本文早期版本相似(相似度 100%)");
        assertThat(duplicate.advice())
                .isEqualTo("本文迭代产生的相似属正常;若需进一步差异化,补充新的素材与细节后再生成");
        assertThat(duplicate.match()).doesNotContain(ORIGINAL);
        assertThat(duplicate.advice()).doesNotContain(ORIGINAL);
    }

    @Test
    void duplicateSameTaskWithoutApplicationUsesTaskCopy() {
        long hash = OriginalityChecker.fingerprint(ORIGINAL).simhash();
        // applicationId 不同（null ≠ context 的 application-1）→ 落分支2（taskId 相同）
        Fingerprint existing = new Fingerprint(
                UUID.randomUUID(), "other-owner", "task-1", null,
                null, null, hash, 40, "generation",
                Instant.parse("2026-08-18T10:00:00Z"));
        when(repository.findCandidates(eq("owner"), eq("task-1"), any()))
                .thenReturn(Flux.just(existing));
        when(repository.insert(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        List<SafetyReport.Finding> findings = checker.checkAndRecord(
                ORIGINAL, context("owner", "task-1")).block();

        assertThat(findings.stream()
                .filter(finding -> finding.category().equals("duplicate_content"))
                .findFirst().orElseThrow().match())
                .isEqualTo("与同任务早期版本相似(相似度 100%)");
    }

    @Test
    void duplicateRecentFingerprintWithinTwoHoursUsesEarlyCheckCopy() {
        long hash = OriginalityChecker.fingerprint(ORIGINAL).simhash();
        // task/application 均不同（复查 manual 落库的行不带 taskId）但 1 小时前刚查过 → 分支3
        Fingerprint existing = new Fingerprint(
                UUID.randomUUID(), "owner", null, null,
                "zhihu", null, hash, 40, "manual",
                Instant.now().minus(java.time.Duration.ofHours(1)));
        when(repository.findCandidates(eq("owner"), isNull(), any()))
                .thenReturn(Flux.just(existing));
        when(repository.insert(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        List<SafetyReport.Finding> findings = checker.checkAndRecord(
                ORIGINAL, context("owner", null)).block();

        assertThat(findings.stream()
                .filter(finding -> finding.category().equals("duplicate_content"))
                .findFirst().orElseThrow().match())
                .isEqualTo("疑似本文早期检查版本(相似度 100%)");
    }

    @Test
    void duplicateCrossTaskShowsMetadataOnly() {
        long hash = OriginalityChecker.fingerprint(ORIGINAL).simhash();
        // task/application 都不同且时间早于 2h 窗口 → 分支4：只显示时间+平台元信息
        Fingerprint existing = new Fingerprint(
                UUID.randomUUID(), "other-owner", "task-9", "application-9",
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
        assertThat(duplicate.match()).isEqualTo("与 2026-08-18 10:00 的douyin创作相似(相似度 100%)");
        assertThat(duplicate.advice()).isEqualTo("与既有创作高度相似,建议重写结构并补充原创信息");
        assertThat(duplicate.match()).doesNotContain(ORIGINAL);
        assertThat(duplicate.advice()).doesNotContain(ORIGINAL);
    }

    @Test
    void duplicateNullPlatformCrossTaskShowsUnknownPlatform() {
        long hash = OriginalityChecker.fingerprint(ORIGINAL).simhash();
        Fingerprint existing = new Fingerprint(
                UUID.randomUUID(), "other-owner", null, null,
                null, null, hash, 40, "generation",
                Instant.parse("2026-08-18T10:00:00Z"));
        when(repository.findCandidates(eq("owner"), isNull(), any()))
                .thenReturn(Flux.just(existing));
        when(repository.insert(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        List<SafetyReport.Finding> findings = checker.checkAndRecord(
                ORIGINAL, context("owner", null)).block();

        assertThat(findings.stream()
                .filter(finding -> finding.category().equals("duplicate_content"))
                .findFirst().orElseThrow().match())
                .isEqualTo("与 2026-08-18 10:00 的未知平台创作相似(相似度 100%)");
    }

    /** 任务书 #63 4.3：相邻 shingle 合并为片段；不足 4 字的片段丢弃。 */
    @Test
    void repeatedFragmentsMergeAdjacentShinglesAndDropShortRuns() {
        var merged = OriginalityChecker.fingerprint("这家咖啡店真不错这家咖啡店真不错");
        assertThat(merged.repeatedFragments()).containsExactly("这家咖啡店真不错", "这家咖啡店真不错");

        // 「好的」重复但仅 2 字（+单个选用 shingle）→ 不足 4 字全部丢弃
        var shortRun = OriginalityChecker.fingerprint("好的好的");
        assertThat(shortRun.repetitionRate()).isGreaterThan(0.30d);
        assertThat(shortRun.repeatedFragments()).isEmpty();
    }

    /** 任务书 #63 4.3：候选多于 5 时按 (count-1)×长度 降序截断 top5。 */
    @Test
    void repeatedFragmentsKeepTopFive() {
        String text = "春天花开春天花开零夏天炎热夏天炎热壹秋天叶落秋天叶落贰冬天雪飘冬天雪飘叁"
                + "春樱满山春樱满山肆夏夜蝉鸣夏夜蝉鸣伍秋高气爽秋高气爽";
        var value = OriginalityChecker.fingerprint(text);
        assertThat(value.repeatedFragments()).hasSize(5);
        // 全部片段都是权重满额的重复短语（无单次出现的混入）
        assertThat(value.repeatedFragments()).allSatisfy(fragment ->
                assertThat(text.split(fragment, -1).length - 1).isGreaterThanOrEqualTo(2));
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

    /** 阈值配置化（任务书 #45 登记）：Hamming 阈值收紧后，原本判重的近邻文本不再命中。 */
    @Test
    void tighterHammingThresholdSuppressesMarginalDuplicate() {
        long hash = OriginalityChecker.fingerprint(ORIGINAL).simhash();
        int distance = OriginalityChecker.hammingDistance(
                hash, OriginalityChecker.fingerprint(SMALL_EDIT).simhash());
        assertThat(distance).isLessThanOrEqualTo(16);
        Fingerprint existing = new Fingerprint(
                UUID.randomUUID(), "owner", null, null,
                null, null, OriginalityChecker.fingerprint(SMALL_EDIT).simhash(), 40, "generation",
                Instant.parse("2026-08-18T10:00:00Z"));
        when(repository.findCandidates(eq("owner"), isNull(), any())).thenReturn(Flux.just(existing));
        when(repository.insert(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        List<SafetyReport.Finding> defaultFindings = checker.checkAndRecord(
                ORIGINAL, context("owner", null)).block();
        assertThat(defaultFindings).anyMatch(finding -> finding.category().equals("duplicate_content"));

        properties.getOriginality().setMaxHammingDistance(distance - 1);
        List<SafetyReport.Finding> tightened = checker.checkAndRecord(
                ORIGINAL, context("owner", null)).block();
        assertThat(tightened).noneMatch(finding -> finding.category().equals("duplicate_content"));
    }

    /** 阈值配置化：重复率上限调高后，同一文本不再报 low_originality。 */
    @Test
    void raisedRepetitionRateThresholdSuppressesLowOriginality() {
        when(repository.findCandidates(eq("owner"), isNull(), any())).thenReturn(Flux.empty());
        when(repository.insert(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        List<SafetyReport.Finding> defaultFindings = checker.checkAndRecord(
                "哈哈哈哈哈哈哈哈哈哈哈哈", context("owner", null)).block();
        assertThat(defaultFindings).anyMatch(finding -> finding.category().equals("low_originality"));

        properties.getOriginality().setMaxRepetitionRate(0.99d);
        List<SafetyReport.Finding> relaxed = checker.checkAndRecord(
                "哈哈哈哈哈哈哈哈哈哈哈哈", context("owner", null)).block();
        assertThat(relaxed).noneMatch(finding -> finding.category().equals("low_originality"));
    }
}
