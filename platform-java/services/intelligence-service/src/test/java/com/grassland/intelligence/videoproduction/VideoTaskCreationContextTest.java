package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.creationcontext.CreationContextSnapshot;
import com.grassland.intelligence.creationcontext.CreationContextSnapshotRepository;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

/**
 * 视频任务创作上下文绑定的形式守卫：朋友圈任务的 contentForm 是 video-text（PRD §4.4），
 * 必须与 video 同样被接受；非视频形式仍 fail-closed。
 */
@ExtendWith(MockitoExtension.class)
class VideoTaskCreationContextTest {

    private static final String ACCOUNT = "acc-1";

    @Mock
    private CreationContextSnapshotRepository snapshots;

    private VideoTaskCreationContext contexts;

    @BeforeEach
    void setUp() {
        contexts = new VideoTaskCreationContext(snapshots);
    }

    @Test
    void acceptsVideoForm() {
        when(snapshots.findById(any())).thenReturn(Mono.just(snapshot("douyin", "video")));

        VideoTaskCreationContext.Binding binding = contexts.bind(UUID.randomUUID(), ACCOUNT, "douyin").block();
        assertThat(binding).isNotNull();
        assertThat(binding.promptContext().content()).contains("视频任务上下文");
    }

    @Test
    void acceptsMomentsVideoTextForm() {
        when(snapshots.findById(any())).thenReturn(Mono.just(snapshot("moments", "video-text")));

        VideoTaskCreationContext.Binding binding = contexts.bind(UUID.randomUUID(), ACCOUNT, "moments").block();
        assertThat(binding).isNotNull();
        assertThat(binding.promptContext().content()).contains("视频任务上下文");
    }

    @Test
    void rejectsGraphicForm() {
        when(snapshots.findById(any())).thenReturn(Mono.just(snapshot("moments", "image-text")));

        assertThatThrownBy(() -> contexts.bind(UUID.randomUUID(), ACCOUNT, "moments")
                .block())
                .isInstanceOf(IntelligenceException.class)
                .hasMessage("创作上下文不是视频任务");
    }

    @Test
    void rejectsPlatformMismatch() {
        when(snapshots.findById(any())).thenReturn(Mono.just(snapshot("douyin", "video")));

        assertThatThrownBy(() -> contexts.bind(UUID.randomUUID(), ACCOUNT, "kuaishou").block())
                .isInstanceOf(IntelligenceException.class)
                .hasMessage("请求平台与冻结的创作上下文不一致");
    }

    /** 任务书 #24：品牌语气与禁止表达必须出现在注入的 prompt 文本里。 */
    @Test
    void injectsStoreBrandingIntoPromptText() {
        when(snapshots.findById(any())).thenReturn(Mono.just(new CreationContextSnapshot(
                UUID.randomUUID(), ACCOUNT, "org-1", "task-1", "app-1", 3,
                "douyin", "video",
                Map.of("title", "视频任务"),
                Map.of("version", "test"),
                Map.of("items", java.util.List.of()),
                Map.of("resolutionType", "PLATFORM"),
                Map.of("storeName", "旗舰店", "brandTone", "温暖亲切",
                        "mustEmphasize", java.util.List.of("锅底现熬"),
                        "forbiddenPhrases", java.util.List.of("最好吃"),
                        "allowedTags", java.util.List.of("#探店")),
                null)));

        VideoTaskCreationContext.Binding binding = contexts.bind(UUID.randomUUID(), ACCOUNT, "douyin").block();
        assertThat(binding).isNotNull();
        String prompt = binding.promptContext().content();
        assertThat(prompt)
                .contains("\"storeBranding\"")
                .contains("品牌语气（风格指令）：温暖亲切")
                .contains("- 锅底现熬")
                .contains("- 最好吃")
                .contains("- #探店");
    }

    /** 无门店品牌快照时 prompt 不携带品牌约束段（既有形状不变）。 */
    @Test
    void promptUnchangedWithoutStoreBranding() {
        when(snapshots.findById(any())).thenReturn(Mono.just(snapshot("douyin", "video")));

        VideoTaskCreationContext.Binding binding = contexts.bind(UUID.randomUUID(), ACCOUNT, "douyin").block();
        assertThat(binding).isNotNull();
        assertThat(binding.promptContext().content())
                .doesNotContain("storeBranding")
                .doesNotContain("门店品牌约束");
    }

    private static CreationContextSnapshot snapshot(String platform, String contentForm) {
        return new CreationContextSnapshot(
                UUID.randomUUID(), ACCOUNT, "org-1", "task-1", "app-1", 3,
                platform, contentForm,
                Map.of("title", "视频任务"),
                Map.of("version", "test"),
                Map.of("items", java.util.List.of()),
                Map.of("resolutionType", "PLATFORM"),
                Map.of(),
                null);
    }
}
