package com.grassland.intelligence.creationstyle;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 创作 style skill 端到端（任务书 #57）：启动种子（22=6+9+7、不重复种）、用户侧目录
 * （仅 enabled、绝不含 promptContent、category 过滤、停用即消失）、治理台（403/全量含
 * promptContent/整行 PUT 乐观锁）。
 */
class CreationStyleSkillIT extends IntelligenceItSupport {

    @Autowired
    private CreationStyleSkillRepository repository;

    @Autowired
    private CreationStyleSkillSeeder seeder;

    // ---------- 启动种子 ----------

    @Test
    @DisplayName("启动种子：22 条 = 6 标题套路 + 9 体裁 + 7 文风（UNIQUE 不炸）")
    void startupSeeds22Skills() {
        assertThat(repository.count().block()).isEqualTo(22L);
        assertThat(repository.listEnabled(CreationStyleSkillCategory.TITLE_FORMULA).collectList().block())
                .hasSize(6);
        assertThat(repository.listEnabled(CreationStyleSkillCategory.GENRE).collectList().block())
                .hasSize(9);
        assertThat(repository.listEnabled(CreationStyleSkillCategory.STYLE).collectList().block())
                .hasSize(7);
    }

    @Test
    @DisplayName("表非空时再跑 Seeder 不重复种（幂等）")
    void reseedIsNoopWhenTableHasRows() {
        seeder.seedOnStartup();
        assertThat(repository.count().block()).isEqualTo(22L);
    }

    // ---------- 用户侧目录 ----------

    @Test
    @DisplayName("目录：无断言 401；登录 22 项、无 promptContent 键；category 过滤正确")
    void catalogListsEnabledOnlyWithoutPromptContent() {
        client().get().uri("/api/creation-style-skills").exchange().expectStatus().isUnauthorized();

        client().get().uri("/api/creation-style-skills")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.skills.length()").isEqualTo(22)
                .jsonPath("$.data.skills[0].promptContent").doesNotExist()
                // 合并目录按 category 字母序（GENRE/STYLE/TITLE_FORMULA）——不假设首项分类，
                // 用过滤式断言锁定「数字型」条目的形状
                .jsonPath("$.data.skills[?(@.code=='number')].name").isEqualTo(java.util.List.of("数字型"))
                .jsonPath("$.data.skills[?(@.code=='number')].category")
                .isEqualTo(java.util.List.of("TITLE_FORMULA"));

        client().get().uri("/api/creation-style-skills?category=GENRE")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.skills.length()").isEqualTo(9);

        client().get().uri("/api/creation-style-skills?category=BOGUS")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("停用即从目录消失（决策 E：随下次拉取生效）")
    void disabledSkillDropsFromCatalog() {
        String id = idOf(CreationStyleSkillCategory.TITLE_FORMULA, "advice");
        String snapshot = snapshotRow(id);
        try {
            client().put().uri("/api/admin/creation-style-skills/" + id)
                    .header("X-Grassland-Identity", signAdmin(UUID.randomUUID().toString()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(updateBody("听劝型", "听劝口吻，激发评论参与", "测试停用", false, 0))
                    .exchange().expectStatus().isOk();

            client().get().uri("/api/creation-style-skills?category=TITLE_FORMULA")
                    .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                    .exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.data.skills.length()").isEqualTo(5)
                    .jsonPath("$.data.skills[?(@.code=='advice')]").isEmpty();
        } finally {
            restoreRow(id, snapshot);
        }
    }

    // ---------- 治理台 ----------

    @Test
    @DisplayName("admin 列表：非 admin 403；admin 全量含 promptContent 与 version")
    void adminListRequiresAdminAndExposesPrompt() {
        client().get().uri("/api/admin/creation-style-skills")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isForbidden();

        client().get().uri("/api/admin/creation-style-skills")
                .header("X-Grassland-Identity", signAdmin(UUID.randomUUID().toString()))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.skills.length()").isEqualTo(22)
                .jsonPath("$.data.skills[0].promptContent").isNotEmpty()
                .jsonPath("$.data.skills[0].version").isEqualTo(0);
    }

    @Test
    @DisplayName("admin PUT：整行更新 version+1；expectedVersion 不符/无此行 → 409；name 空 → 400")
    void adminUpdateWithOptimisticLock() {
        String id = idOf(CreationStyleSkillCategory.GENRE, "review");
        String snapshot = snapshotRow(id);
        try {
            client().put().uri("/api/admin/creation-style-skills/" + id)
                    .header("X-Grassland-Identity", signAdmin("30303030-3030-3030-3030-303030303030"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(updateBody("种草测评型", "实测分维度，结论明确不骑墙",
                            "修订后的测评体裁指令", true, 0))
                    .exchange().expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.data.skill.version").isEqualTo(1)
                    .jsonPath("$.data.skill.promptContent").isEqualTo("修订后的测评体裁指令");

            // 版本已到 1：再按 0 提交 → 409
            client().put().uri("/api/admin/creation-style-skills/" + id)
                    .header("X-Grassland-Identity", signAdmin(UUID.randomUUID().toString()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(updateBody("种草测评型", "x", "y", true, 0))
                    .exchange().expectStatus().isEqualTo(409);

            // 不存在的行 → 409（不存在/冲突同文案，均要求刷新）
            client().put().uri("/api/admin/creation-style-skills/" + UUID.randomUUID())
                    .header("X-Grassland-Identity", signAdmin(UUID.randomUUID().toString()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(updateBody("名", "x", "y", true, 0))
                    .exchange().expectStatus().isEqualTo(409);

            // 校验：name 空 / promptContent 空 / 缺 expectedVersion
            client().put().uri("/api/admin/creation-style-skills/" + id)
                    .header("X-Grassland-Identity", signAdmin(UUID.randomUUID().toString()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(updateBody(" ", "x", "y", true, 1))
                    .exchange().expectStatus().isBadRequest();
            client().put().uri("/api/admin/creation-style-skills/" + id)
                    .header("X-Grassland-Identity", signAdmin(UUID.randomUUID().toString()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(updateBody("名", "x", " ", true, 1))
                    .exchange().expectStatus().isBadRequest();
            client().put().uri("/api/admin/creation-style-skills/" + id)
                    .header("X-Grassland-Identity", signAdmin(UUID.randomUUID().toString()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("name", "名", "description", "x", "promptContent", "y", "enabled", true))
                    .exchange().expectStatus().isBadRequest();
        } finally {
            restoreRow(id, snapshot);
        }
    }

    // ---------- helpers ----------

    private static Map<String, Object> updateBody(String name, String description, String promptContent,
            boolean enabled, int expectedVersion) {
        return Map.of("name", name, "description", description, "promptContent", promptContent,
                "enabled", enabled, "expectedVersion", expectedVersion);
    }

    private String idOf(CreationStyleSkillCategory category, String code) {
        return repository.findByCode(category, code).block().id().toString();
    }

    /** 测前快照（promptContent/version/enabled），测后原样还原——共享库里种子行是全套件公共前提。 */
    private String snapshotRow(String id) {
        return db.sql("SELECT prompt_content FROM creation_style_skill WHERE id = CAST(:id AS uuid)")
                .bind("id", id).map(r -> r.get(0, String.class)).one().block();
    }

    private void restoreRow(String id, String originalPrompt) {
        db.sql("UPDATE creation_style_skill SET prompt_content = :p, enabled = true, version = 0, "
                + "updated_by = NULL, updated_at = now() WHERE id = CAST(:id AS uuid)")
                .bind("p", originalPrompt).bind("id", id).then().block();
    }
}
