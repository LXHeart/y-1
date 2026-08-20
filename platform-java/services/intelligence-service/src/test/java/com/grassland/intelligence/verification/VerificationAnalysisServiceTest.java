package com.grassland.intelligence.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/**
 * {@link VerificationAnalysisService} provider guard 单元测试（草场 Slice 11 Verification Stage 3）。
 * provider 非 qwen → analyze 直接 400（部署配置错误信号），不经任何媒体/AI 路径。
 */
class VerificationAnalysisServiceTest {

    @Test
    @DisplayName("provider 非 qwen 时 analyze 返回 400（镜像 VideoRecreationAdaptationService provider guard）")
    void nonQwenProviderRejectedWith400() {
        Environment env = mock(Environment.class);
        when(env.getProperty("ai.verification.provider", "qwen")).thenReturn("coze");
        when(env.getProperty(eq("ai.verification.timeout-ms"), eq(Long.class), anyLong())).thenReturn(60000L);

        VerificationAnalysisService service = new VerificationAnalysisService(
                mock(AiCapabilityAdapter.class),
                mock(MediaReferenceRepository.class),
                mock(ObjectStorageAdapter.class),
                new VerificationResultNormalizer(),
                env);

        VerificationAnalysisRequest req =
                new VerificationAnalysisRequest(List.of(UUID.randomUUID()), "任务标题", null, "douyin");

        assertThatThrownBy(() -> service.analyze(req).block())
                .isInstanceOf(IntelligenceException.class)
                .satisfies(e -> assertThat(((IntelligenceException) e).status()).isEqualTo(400));
    }

    // ---------- 任务书 #23：互动截图核验（mode=interaction） ----------

    @Test
    @DisplayName("mode 默认 visual（零改动），interaction 显式开启")
    void modeDefaultsToVisualAndInteractionIsExplicit() {
        VerificationAnalysisRequest legacy =
                new VerificationAnalysisRequest(List.of(UUID.randomUUID()), "任务标题", null, "douyin");
        assertThat(legacy.interactionMode()).isFalse();

        VerificationAnalysisRequest interaction = new VerificationAnalysisRequest(
                List.of(UUID.randomUUID()), "任务标题", null, "douyin",
                "interaction", "https://www.xiaohongshu.com/post/1", "like", "@seedhunter");
        assertThat(interaction.interactionMode()).isTrue();
    }

    @Test
    @DisplayName("互动 prompt 携带目标链接/动作类型/账号标识与三项判定标准")
    void interactionPromptCarriesContextAndCriteria() {
        String prompt = VerificationPrompts.buildInteraction(
                "给笔记点赞", null, "xiaohongshu",
                "https://www.xiaohongshu.com/post/1", "like", "@seedhunter", null);

        assertThat(prompt).contains("https://www.xiaohongshu.com/post/1");
        assertThat(prompt).contains("已点赞");
        assertThat(prompt).contains("@seedhunter");
        assertThat(prompt).contains("动作状态");
        assertThat(prompt).contains("账号").contains("一致");
        assertThat(prompt).contains("passed").contains("failed").contains("inconclusive");
    }

    @Test
    @DisplayName("follow/favorite 动作类型映射为中文动作词")
    void interactionPromptMapsActionTypes() {
        assertThat(VerificationPrompts.buildInteraction("t", null, null,
                "https://www.douyin.com/video/1", "follow", "@a", null))
                .contains("已关注");
        assertThat(VerificationPrompts.buildInteraction("t", null, null,
                "https://www.douyin.com/video/1", "favorite", "@a", null))
                .contains("已收藏");
    }

// ---------- 缺口清偿之九：评论动作与评论文本一致性判定 ----------

@Test
void interactionPromptIncludesCommentActionAndFourthCheck() {
    String prompt = VerificationPrompts.buildInteraction(
            "评论互动任务", null, "xiaohongshu",
            "https://www.xiaohongshu.com/post/1", "comment", "@seedhunter",
            "这家店的桂花拿铁真的很惊艳！");

    org.assertj.core.api.Assertions.assertThat(prompt)
            .contains("已评论")
            .contains("推荐官申报的评论内容：这家店的桂花拿铁真的很惊艳！")
            .contains("4. 截图中可见的评论内容是否与申报的评论内容一致");
}

@Test
void interactionPromptWithoutCommentKeepsThreeChecks() {
    String prompt = VerificationPrompts.buildInteraction(
            "点赞任务", null, "douyin", "https://v.douyin.com/x", "like", "@fan", null);

    org.assertj.core.api.Assertions.assertThat(prompt)
            .contains("已点赞")
            .doesNotContain("申报的评论内容")
            .doesNotContain("4. 截图中可见的评论内容");
}
}
