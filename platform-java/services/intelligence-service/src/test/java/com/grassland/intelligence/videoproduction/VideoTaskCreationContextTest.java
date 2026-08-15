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

    private static CreationContextSnapshot snapshot(String platform, String contentForm) {
        return new CreationContextSnapshot(
                UUID.randomUUID(), ACCOUNT, "org-1", "task-1", "app-1", 3,
                platform, contentForm,
                Map.of("title", "视频任务"),
                Map.of("version", "test"),
                Map.of("items", java.util.List.of()),
                Map.of("resolutionType", "PLATFORM"),
                null);
    }
}
