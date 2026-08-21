package com.grassland.intelligence.creationlineage;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.creationlineage.CreationGeneration.Kind;
import com.grassland.intelligence.creationlineage.CreationGeneration.Mode;
import com.grassland.intelligence.creationlineage.CreationGeneration.Resolution;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CreationGenerationRepositoryIT extends IntelligenceItSupport {

    private static final String IDENTITY = "X-Grassland-Identity";

    @Autowired
    private CreationGenerationRepository repository;

    @BeforeEach
    void cleanLineage() {
        db.sql("DELETE FROM creation_generation").then().block();
        db.sql("DELETE FROM media_reference WHERE owner_account_id LIKE 'lineage-%'").then().block();
    }

    @Test
    void repositoryEnforcesOwnerKindAndStableKeysetPagination() {
        CreationGeneration oldest = insert("lineage-owner", Kind.ASSET_IMAGE, "oldest", List.of());
        CreationGeneration middle = insert("lineage-owner", Kind.SCENE_IMAGE, "middle", List.of());
        CreationGeneration newest = insert("lineage-owner", Kind.ASSET_IMAGE, "newest", List.of());
        insert("lineage-other", Kind.ASSET_IMAGE, "other-owner", List.of());
        setCreatedAt(oldest.id(), "2026-08-19T01:00:00Z");
        setCreatedAt(middle.id(), "2026-08-19T02:00:00Z");
        setCreatedAt(newest.id(), "2026-08-19T03:00:00Z");

        List<CreationGeneration> first = repository.listForOwner(
                "lineage-owner", null, 2, null).collectList().block();
        List<CreationGeneration> second = repository.listForOwner(
                "lineage-owner", null, 2, first.getLast().id()).collectList().block();
        List<CreationGeneration> assets = repository.listForOwner(
                "lineage-owner", Kind.ASSET_IMAGE, 10, null).collectList().block();

        assertThat(first).extracting(CreationGeneration::id)
                .containsExactly(newest.id(), middle.id());
        assertThat(second).extracting(CreationGeneration::id).containsExactly(oldest.id());
        assertThat(assets).extracting(CreationGeneration::id)
                .containsExactly(newest.id(), oldest.id());
        assertThat(repository.findByIdAndOwner(newest.id(), "lineage-other").block()).isNull();
    }

    @Test
    void listForOrganizationScopesToOrgMembersAndSupportsKindCursorPagination() {
        CreationGeneration oldest = insert("lineage-a", "lineage-org", Kind.ARTICLE, "oldest");
        CreationGeneration newest = insert("lineage-b", "lineage-org", Kind.MOMENTS_COPY, "newest");
        insert("lineage-a", "lineage-other-org", Kind.SCENE_IMAGE, "别的组织");
        insert("lineage-a", null, Kind.SCENE_IMAGE, "独立创作无组织");
        setCreatedAt(oldest.id(), "2026-08-19T01:00:00Z");
        setCreatedAt(newest.id(), "2026-08-19T03:00:00Z");

        List<CreationGeneration> page = repository.listForOrganization(
                "lineage-org", null, 10, null).collectList().block();
        List<CreationGeneration> articles = repository.listForOrganization(
                "lineage-org", Kind.ARTICLE, 10, null).collectList().block();
        List<CreationGeneration> tail = repository.listForOrganization(
                "lineage-org", null, 1, newest.id()).collectList().block();

        assertThat(page).extracting(CreationGeneration::id).containsExactly(newest.id(), oldest.id());
        assertThat(page).extracting(CreationGeneration::ownerAccountId)
                .containsExactly("lineage-b", "lineage-a");
        assertThat(articles).extracting(CreationGeneration::id).containsExactly(oldest.id());
        assertThat(tail).extracting(CreationGeneration::id).containsExactly(oldest.id());
    }

    @Test
    void readApiHidesListPayloadAndReportsMediaAvailabilityWithoutCrossOwnerLeaks() {
        UUID available = seedMedia(false);
        UUID expired = seedMedia(true);
        CreationGeneration generation = insert(
                "lineage-owner", Kind.ASSET_IMAGE, "public title", List.of(available, expired));

        client().get().uri("/api/creation-generations")
                .exchange().expectStatus().isUnauthorized();
        String list = new String(client().get().uri("/api/creation-generations?kind=asset_image")
                .header(IDENTITY, sign("lineage-owner", "recommender"))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(list).contains("2 张图片");
        assertThat(list).doesNotContain("secret prompt", "secret result body", "inputSummary");

        client().get().uri("/api/creation-generations/" + generation.id())
                .header(IDENTITY, sign("lineage-other", "recommender"))
                .exchange().expectStatus().isNotFound();
        client().get().uri("/api/creation-generations/" + generation.id())
                .header(IDENTITY, sign("lineage-owner", "recommender"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.promptText").isEqualTo("secret prompt")
                .jsonPath("$.data.result.secret").isEqualTo("secret result body")
                .jsonPath("$.data.resultMedia[0].mediaId").isEqualTo(available.toString())
                .jsonPath("$.data.resultMedia[0].available").isEqualTo(true)
                .jsonPath("$.data.resultMedia[1].mediaId").isEqualTo(expired.toString())
                .jsonPath("$.data.resultMedia[1].available").isEqualTo(false);
    }

    private CreationGeneration insert(String owner, String organizationId, Kind kind, String title) {
        return insert(owner, organizationId, kind, title, List.of());
    }

    private CreationGeneration insert(String owner, Kind kind, String title, List<UUID> mediaIds) {
        return insert(owner, null, kind, title, mediaIds);
    }

    private CreationGeneration insert(String owner, String organizationId, Kind kind, String title,
            List<UUID> mediaIds) {
        List<Map<String, Object>> images = mediaIds.stream().map(id -> Map.<String, Object>of(
                "mediaId", id, "imageUrl", "/api/article-generation/generated-images/" + id,
                "size", "1024x1024")).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("adaptedTitle", title);
        result.put("images", images);
        result.put("secret", "secret result body");
        return repository.insert(new CreationGeneration(
                null, owner, organizationId, kind, Mode.INDEPENDENT, null, null,
                Resolution.PLATFORM, "qwen", "wanx-v1", 1, "upstream",
                "secret prompt", Map.of("topic", "secret input"), List.of(),
                result, mediaIds, null)).block();
    }

    private UUID seedMedia(boolean expired) {
        UUID id = UUID.randomUUID();
        String sql = """
                INSERT INTO media_reference(
                    id, owner_account_id, purpose, object_key, mime_type, size_bytes,
                    source, status, expires_at)
                VALUES (CAST(:id AS uuid), 'lineage-owner', 'video_asset', :objectKey,
                    'image/png', 8, 'generated', 'active',
                    %s)
                """.formatted(expired ? "now()-interval '1 second'" : "now()+interval '1 hour'");
        db.sql(sql).bind("id", id.toString()).bind("objectKey", "lineage/" + id).then().block();
        return id;
    }

    private void setCreatedAt(UUID id, String value) {
        db.sql("UPDATE creation_generation SET created_at=CAST(:createdAt AS timestamptz)"
                        + " WHERE id=CAST(:id AS uuid)")
                .bind("createdAt", value).bind("id", id.toString()).then().block();
    }
}
