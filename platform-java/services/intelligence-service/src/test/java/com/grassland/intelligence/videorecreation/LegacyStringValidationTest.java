package com.grassland.intelligence.videorecreation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LegacyStringValidationTest {
    @Test
    void trimsEcmaWhitespaceIncludingNbspAndBom() {
        assertThat(LegacyStringValidation.trim(" ﻿1024x1792 "))
                .isEqualTo("1024x1792");
        assertThat(LegacyStringValidation.trim(" ﻿ ")).isEmpty();
    }

    @Test
    void sceneRequiredOptionalStringsRejectMissingButAllowEmpty() {
        assertThatThrownBy(() -> VideoScene.parse(Map.of(
                "shotDescription", "镜头", "characterDescription", "角色", "sceneEnvironment", "夜景")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(VideoScene.parse(Map.of(
                "shotDescription", "镜头", "characterDescription", "角色",
                "actionMovement", "", "dialogueVoiceover", "", "sceneEnvironment", "夜景")))
                .isNotNull();
    }
}
