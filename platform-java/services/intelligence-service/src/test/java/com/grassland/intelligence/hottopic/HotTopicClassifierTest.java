package com.grassland.intelligence.hottopic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HotTopicClassifierTest {

    private HotTopicClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new HotTopicClassifier(new HotTopicTaxonomy());
    }

    @Test
    void classifiesMultipleIndustriesCityAndContentType() {
        HotTopicTags tags = classifier.classify("上海AI发布会带火奶茶美妆联名");

        assertThat(tags.industries()).containsExactly("catering", "beauty");
        assertThat(tags.city()).isEqualTo("上海");
        assertThat(tags.contentType()).isEqualTo("tech");
        assertThat(tags.taxonomyVersion()).isEqualTo("hot-taxonomy-v1");
    }

    @Test
    void noMatchUsesUnclassifiedBucket() {
        HotTopicTags tags = classifier.classify("普通社会话题讨论");

        assertThat(tags.industries()).isEmpty();
        assertThat(tags.city()).isNull();
        assertThat(tags.contentType()).isNull();
    }

    @Test
    void latinTermsRequireLatinBoundariesButAllowChineseNeighbors() {
        assertThat(HotTopicClassifier.matches("AI芯片发布", "AI")).isTrue();
        assertThat(HotTopicClassifier.matches("用AI做内容", "AI")).isTrue();
        assertThat(HotTopicClassifier.matches("RAIL技术", "AI")).isFalse();
        assertThat(HotTopicClassifier.matches("Steam游戏节", "Steam")).isTrue();
        assertThat(HotTopicClassifier.matches("SteamDeck发布", "Steam")).isFalse();
    }
}
