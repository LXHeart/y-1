package com.grassland.intelligence.contentsafety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 内容安全阈值配置（任务书 #45 登记）：env 绑定 + 越界 fail-fast；默认值 = 代码写死时期取值。 */
class ContentSafetyPropertiesTest {

    @Test
    void defaultsMatchFormerHardcodedThresholds() {
        ContentSafetyProperties properties = new ContentSafetyProperties();
        assertThat(properties.getOriginality().getMaxHammingDistance()).isEqualTo(16);
        assertThat(properties.getOriginality().getMaxRepetitionRate()).isEqualTo(0.30d);
        assertThat(properties.getDeepCheckMinChars()).isEqualTo(200);
    }

    @Test
    void envKeysOverrideThresholds() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "content-safety.originality.max-hamming-distance", "8",
                "content-safety.originality.max-repetition-rate", "0.15",
                "content-safety.deep-check-min-chars", "120"));
        ContentSafetyProperties bound = new Binder(source)
                .bind("content-safety", ContentSafetyProperties.class).get();
        assertThat(bound.getOriginality().getMaxHammingDistance()).isEqualTo(8);
        assertThat(bound.getOriginality().getMaxRepetitionRate()).isEqualTo(0.15d);
        assertThat(bound.getDeepCheckMinChars()).isEqualTo(120);
    }

    @Test
    void outOfRangeThresholdsFailFast() {
        ContentSafetyProperties hamming = new ContentSafetyProperties();
        hamming.getOriginality().setMaxHammingDistance(65);
        assertThatThrownBy(hamming::validate).isInstanceOf(IllegalStateException.class);

        ContentSafetyProperties repetition = new ContentSafetyProperties();
        repetition.getOriginality().setMaxRepetitionRate(1.5d);
        assertThatThrownBy(repetition::validate).isInstanceOf(IllegalStateException.class);

        ContentSafetyProperties deepCheck = new ContentSafetyProperties();
        deepCheck.setDeepCheckMinChars(0);
        assertThatThrownBy(deepCheck::validate).isInstanceOf(IllegalStateException.class);
    }
}
