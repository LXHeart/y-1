package com.grassland.intelligence.article;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.article.ArticlePrompts.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link ArticlePrompts} 平台解析 + 各类 prompt/用户消息组装（忠实移植 legacy）。 */
class ArticlePromptsTest {

    @Test
    @DisplayName("Platform.fromKey：大小写不敏感；未知/缺失 → wechat")
    void platformFromKey() {
        assertThat(Platform.fromKey("wechat")).isEqualTo(Platform.WECHAT);
        assertThat(Platform.fromKey("ZHIHU")).isEqualTo(Platform.ZHIHU);
        assertThat(Platform.fromKey("xiaohongshu")).isEqualTo(Platform.XIAOHONGSHU);
        assertThat(Platform.fromKey(null)).isEqualTo(Platform.WECHAT);
        assertThat(Platform.fromKey("")).isEqualTo(Platform.WECHAT);
        assertThat(Platform.fromKey("twitter")).isEqualTo(Platform.WECHAT);
    }

    @Test
    @DisplayName("各类 system prompt 按平台取，未知平台回退 wechat")
    void systemPromptsByPlatform() {
        assertThat(ArticlePrompts.titlesSystem(Platform.WECHAT).content()).contains("标题策划师");
        assertThat(ArticlePrompts.outlineSystem(Platform.ZHIHU).content()).contains("知乎");
        assertThat(ArticlePrompts.contentSystem(Platform.XIAOHONGSHU).content()).contains("小红书");
        assertThat(ArticlePrompts.titlesSystem(null).content()).contains("标题策划师");
    }

    @Test
    @DisplayName("用户消息按 legacy 格式组装")
    void userMessages() {
        ChatMessage titles = ArticlePrompts.titlesUser("职场");
        assertThat(titles.content()).isEqualTo("主题：职场");

        ChatMessage outline = ArticlePrompts.outlineUser("职场", "打工人的清晨");
        assertThat(outline.content()).isEqualTo("主题：职场\n标题：打工人的清晨");

        ChatMessage content = ArticlePrompts.contentUser("职场", "打工人的清晨", "一、开头\n二、展开");
        assertThat(content.content()).contains("主题：职场").contains("标题：打工人的清晨")
                .contains("大纲：").contains("一、开头");
    }
}
