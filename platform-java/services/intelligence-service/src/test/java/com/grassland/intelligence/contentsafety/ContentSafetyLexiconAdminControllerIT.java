package com.grassland.intelligence.contentsafety;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

@SuppressWarnings("unchecked")
class ContentSafetyLexiconAdminControllerIT extends IntelligenceItSupport {

    private static final String IDENTITY = "X-Grassland-Identity";

    @Autowired
    private ContentSafetyLexicon lexicons;

    @BeforeEach
    void resetLexicon() {
        restoreSeed();
    }

    @AfterEach
    void restoreLexiconForOtherTests() {
        restoreSeed();
    }

    @Test
    void seedAndAdminPermissionsAreEnforced() {
        Long active = db.sql("SELECT COUNT(*)::bigint AS count FROM content_safety_lexicon_version"
                        + " WHERE status='active' AND label='lexicon-v1'")
                .map(row -> row.get("count", Long.class)).one().block();
        assertThat(active).isEqualTo(1L);

        client().get().uri("/api/admin/content-safety/lexicons")
                .exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/admin/content-safety/lexicons")
                .header(IDENTITY, sign("user", "recommender"))
                .exchange().expectStatus().isForbidden();

        String response = new String(client().get().uri("/api/admin/content-safety/lexicons")
                .header(IDENTITY, signAdmin("admin"))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(response).contains("lexicon-v1").doesNotContain("\"payload\"");
    }

    @Test
    void draftActivationInvalidatesCacheAndRetiresPreviousVersionAtomically() {
        Map<String, Object> created = create("lexicon-v2", validPayload("lexicon-v2"));
        String id = ((Map<String, Object>) created.get("data")).get("id").toString();

        client().post().uri("/api/admin/content-safety/lexicons/" + id + "/activate")
                .header(IDENTITY, signAdmin("admin"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("active");

        List<Map<String, Object>> statuses = db.sql(
                        "SELECT label, status FROM content_safety_lexicon_version ORDER BY label")
                .map(row -> Map.<String, Object>of(
                        "label", row.get("label", String.class),
                        "status", row.get("status", String.class)))
                .all().collectList().block();
        assertThat(statuses).containsExactly(
                Map.of("label", "lexicon-v1", "status", "retired"),
                Map.of("label", "lexicon-v2", "status", "active"));
        assertThat(statuses).filteredOn(item -> item.get("status").equals("active")).hasSize(1);

        client().post().uri("/api/content-safety/check")
                .header(IDENTITY, sign("user", "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", "这是刚激活的新运营词"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.safety.lexiconVersion").isEqualTo("lexicon-v2")
                .jsonPath("$.data.safety.findings[0].match").isEqualTo("新运营词");

        client().post().uri("/api/admin/content-safety/lexicons/" + id + "/retire")
                .header(IDENTITY, signAdmin("admin"))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void duplicateLabelAndInvalidRegexAreRejected() {
        create("lexicon-v2", validPayload("lexicon-v2"));
        client().post().uri("/api/admin/content-safety/lexicons")
                .header(IDENTITY, signAdmin("admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("label", "lexicon-v2", "payload", validPayload("lexicon-v2")))
                .exchange().expectStatus().isEqualTo(409);

        Map<String, Object> bad = Map.of(
                "version", "lexicon-v3",
                "categories", List.of(Map.of(
                        "id", "bad", "severity", "low", "advice", "改写",
                        "phrases", List.of(),
                        "patterns", List.of(Map.of("id", "broken", "regex", "[")))));
        client().post().uri("/api/admin/content-safety/lexicons")
                .header(IDENTITY, signAdmin("admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("label", "lexicon-v3", "payload", bad))
                .exchange().expectStatus().isBadRequest();
    }

    private Map<String, Object> create(String label, Map<String, Object> payload) {
        return client().post().uri("/api/admin/content-safety/lexicons")
                .header(IDENTITY, signAdmin("admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("label", label, "payload", payload))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
    }

    private static Map<String, Object> validPayload(String version) {
        return Map.of(
                "version", version,
                "categories", List.of(Map.of(
                        "id", "operations", "severity", "low", "advice", "改写",
                        "phrases", List.of("新运营词"), "patterns", List.of())));
    }

    private void restoreSeed() {
        db.sql("DELETE FROM content_safety_lexicon_version").then().block();
        lexicons.invalidate();
        lexicons.seedOnStartup();
    }
}
