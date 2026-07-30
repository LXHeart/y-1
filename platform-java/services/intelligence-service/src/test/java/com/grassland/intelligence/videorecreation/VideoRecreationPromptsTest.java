package com.grassland.intelligence.videorecreation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link VideoRecreationPrompts} 忠实移植 legacy 的 golden-string 单元测试（草场 Slice 9）。 */
class VideoRecreationPromptsTest {

    @Test
    void buildsScenePromptWithActionAndStyleJoinedByPeriodSpace() {
        VideoScene scene = new VideoScene("镜头A", "角色X", "走动", "旁白内容", "夜景");

        String prompt = VideoRecreationPrompts.buildSceneImagePrompt(scene, "水墨");

        assertThat(prompt).isEqualTo("镜头A. 角色X. 夜景. Action: 走动. Style: 水墨");
    }

    @Test
    void buildsScenePromptWithoutOptionalActionAndStyle() {
        VideoScene scene = new VideoScene("镜头A", "角色X", null, null, "夜景");

        assertThat(VideoRecreationPrompts.buildSceneImagePrompt(scene, null))
                .isEqualTo("镜头A. 角色X. 夜景");
    }

    @Test
    void scenePromptAppendsActionBeforeStyleButOmitsDialogue() {
        VideoScene scene = new VideoScene("镜头A", "角色X", "奔跑", "秘密旁白不应出现", "夜景");

        String prompt = VideoRecreationPrompts.buildSceneImagePrompt(scene, null);

        assertThat(prompt).isEqualTo("镜头A. 角色X. 夜景. Action: 奔跑");
        assertThat(prompt).doesNotContain("秘密旁白");
    }

    @Test
    void buildsCharacterAssetPromptWithStyle() {
        Asset.CharacterAsset asset = new Asset.CharacterAsset("a1", "角色名", "角色描述", "三视图提示");

        assertThat(VideoRecreationPrompts.buildAssetImagePrompt(asset, "卡通"))
                .isEqualTo("角色名. 角色描述. 三视图提示. Style: 卡通");
    }

    @Test
    void buildsSceneAssetPromptIncludingOptionalTitle() {
        Asset.SceneAsset asset = new Asset.SceneAsset("s1", "场景标题", "场景描述", "画面提示");

        assertThat(VideoRecreationPrompts.buildAssetImagePrompt(asset, null))
                .isEqualTo("场景标题. 场景描述. 画面提示");
    }

    @Test
    void buildsSceneAssetPromptOmittingAbsentTitle() {
        Asset.SceneAsset asset = new Asset.SceneAsset("s1", null, "场景描述", "画面提示");

        assertThat(VideoRecreationPrompts.buildAssetImagePrompt(asset, null))
                .isEqualTo("场景描述. 画面提示");
    }

    @Test
    void buildsPropAssetPrompt() {
        Asset.PropAsset asset = new Asset.PropAsset("p1", "道具名", "道具描述", "画面提示");

        assertThat(VideoRecreationPrompts.buildAssetImagePrompt(asset, "写实"))
                .isEqualTo("道具名. 道具描述. 画面提示. Style: 写实");
    }
}
