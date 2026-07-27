package com.grassland.intelligence.comedy;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.ai.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link ComedyPrompts} 忠实移植 legacy：duration/wordCount 替换、wordCount = duration×4.5、主题插值。 */
class ComedyPromptsTest {

    @Test
    @DisplayName("system 提示替换 {duration}/{wordCount}，残留占位为零")
    void systemSubstitutesPlaceholders() {
        ChatMessage m = ComedyPrompts.system(60);
        assertThat(m.role()).isEqualTo("system");
        assertThat(m.content()).contains("总时长约 60 秒（约 270 字）");   // 60 × 4.5 = 270
        assertThat(m.content()).doesNotContain("{duration}").doesNotContain("{wordCount}");
        // 风格锚点保留（移植完整性）
        assertThat(m.content()).contains("李继刚").contains("【铺垫】").contains("【爆点】");
    }

    @Test
    @DisplayName("wordCount 随 duration 缩放（90→405、30→135）")
    void wordCountScalesWithDuration() {
        assertThat(ComedyPrompts.system(90).content()).contains("约 405 字");
        assertThat(ComedyPrompts.system(30).content()).contains("约 135 字");
    }

    @Test
    @DisplayName("user 消息嵌入主题")
    void userMessageEmbedsTopic() {
        assertThat(ComedyPrompts.user("职场加班").content()).contains("职场加班");
    }
}
