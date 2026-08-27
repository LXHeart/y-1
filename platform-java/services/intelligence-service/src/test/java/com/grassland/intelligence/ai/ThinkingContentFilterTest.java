package com.grassland.intelligence.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ThinkingContentFilter")
class ThinkingContentFilterTest {

    @Test
    @DisplayName("strip：无思考块时原样返回")
    void stripPassthrough() {
        assertThat(ThinkingContentFilter.strip("{\"titles\":[]}")).isEqualTo("{\"titles\":[]}");
        assertThat(ThinkingContentFilter.strip("")).isEqualTo("");
        assertThat(ThinkingContentFilter.strip(null)).isNull();
    }

    @Test
    @DisplayName("strip：剥掉单个思考块保留前后正文")
    void stripsSingleBlock() {
        String content = "<think>\n先想一下受众\n</think>\n\n```json\n{\"titles\":[{\"title\":\"t\"}]}\n```";
        assertThat(ThinkingContentFilter.strip(content))
                .isEqualTo("\n\n```json\n{\"titles\":[{\"title\":\"t\"}]}\n```");
    }

    @Test
    @DisplayName("strip：多个思考块全部剥掉")
    void stripsMultipleBlocks() {
        assertThat(ThinkingContentFilter.strip("a<think>x</think>b<think>y</think>c")).isEqualTo("abc");
    }

    @Test
    @DisplayName("strip：未闭合的思考块视为截断，其后内容丢弃")
    void dropsUnclosedBlock() {
        assertThat(ThinkingContentFilter.strip("a<think>没想完")).isEqualTo("a");
    }

    @Test
    @DisplayName("流式：标签被 token 边界切开仍能正确剥离")
    void streamHandlesSplitTags() {
        ThinkingContentFilter.Stream stripper = new ThinkingContentFilter.Stream();
        assertThat(stripper.feed("<th")).isEmpty();
        assertThat(stripper.feed("ink>思")).isEmpty();
        assertThat(stripper.feed("考中</th")).isEmpty();
        assertThat(stripper.feed("ink>正文A")).isEqualTo("正文A");
        assertThat(stripper.flush()).isEmpty();
    }

    @Test
    @DisplayName("流式：疑似标签前缀的正文尾部会被扣住并在 flush 释放")
    void streamFlushesHeldBackSuffix() {
        ThinkingContentFilter.Stream stripper = new ThinkingContentFilter.Stream();
        assertThat(stripper.feed("正文以 < 结尾 <thi")).isEqualTo("正文以 < 结尾 ");
        assertThat(stripper.flush()).isEqualTo("<thi");
    }

    @Test
    @DisplayName("流式：思考未闭合即断流时残余全部丢弃")
    void streamDropsUnclosedThinkOnFlush() {
        ThinkingContentFilter.Stream stripper = new ThinkingContentFilter.Stream();
        assertThat(stripper.feed("<think>想了一半")).isEmpty();
        assertThat(stripper.flush()).isEmpty();
    }

    @Test
    @DisplayName("流式：一次 feed 内含多个思考块全部消化")
    void streamHandlesMultipleBlocksInOneChunk() {
        ThinkingContentFilter.Stream stripper = new ThinkingContentFilter.Stream();
        assertThat(stripper.feed("a<think>x</think>b<think>y</think>c")).isEqualTo("abc");
    }

    @Test
    @DisplayName("流式：思考态下可能被切断的闭合标签前缀保留到下一块")
    void streamKeepsClosePrefixAcrossChunks() {
        ThinkingContentFilter.Stream stripper = new ThinkingContentFilter.Stream();
        assertThat(stripper.feed("<think>思考</")).isEmpty();
        assertThat(stripper.feed("think>答案")).isEqualTo("答案");
    }

    @Test
    @DisplayName("流式：正文里的孤立闭合标签按正文原样透传")
    void streamPassesStrayCloseTagThrough() {
        ThinkingContentFilter.Stream stripper = new ThinkingContentFilter.Stream();
        assertThat(stripper.feed("a</think>b")).isEqualTo("a</think>b");
    }

    @Test
    @DisplayName("流式与 strip 在同源内容上结果一致")
    void streamAgreesWithStrip() {
        String content = "<think>abc</think>第一段<think>def</think>第二段";
        List<String> collected = new ArrayList<>();
        ThinkingContentFilter.Stream stripper = new ThinkingContentFilter.Stream();
        for (String chunk : content.split("(?<=。)|(?<=段)")) {
            collected.add(stripper.feed(chunk));
        }
        collected.add(stripper.flush());
        assertThat(String.join("", collected)).isEqualTo(ThinkingContentFilter.strip(content));
    }
}
