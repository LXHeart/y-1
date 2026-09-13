package com.grassland.intelligence.creationcanvas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.IntelligenceItSupport;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"ai.video-generation.worker-enabled=false"})
class CanvasPlanContractIT extends IntelligenceItSupport {
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String ACCOUNT = "10206000-0000-4000-8000-000000000099";
	private JsonNode examples() throws Exception {
		return MAPPER.readTree(getClass().getResourceAsStream("/contracts/canvas-plan-v1.examples.json"));
	}

	@Test
	void sharedValidAndInvalidActionsAreStrictAndStoredWrappersNormalize() throws Exception {
		JsonNode fixture = examples();
		Set<String> selected = Set.of(fixture.path("selectedShotIds").get(0).asText(),
				fixture.path("selectedShotIds").get(1).asText());
		for (JsonNode row : fixture.path("valid")) {
			JsonNode action = row.path("action");
			String canonical = CanvasAgentPlan.parseAction(action.toString());
			assertThat(MAPPER.readTree(canonical)).isEqualTo(action);
			CanvasAgentPlan.validateAgainstContext(canonical, selected, 1, 2);
			var value = ((com.fasterxml.jackson.databind.node.ObjectNode) action).deepCopy();
			String kind = value.remove("kind").asText();
			String old = MAPPER.createObjectNode().set(kind, value).toString();
			assertThat(MAPPER.readTree(CanvasAgentPlan.decodeStoredAction(old))).isEqualTo(action);
		}
		for (JsonNode row : fixture.path("invalid"))
			assertThatIllegalArgumentException().as(row.path("name").asText())
					.isThrownBy(() -> CanvasAgentPlan.parseAction(row.path("action").toString()));
		String append = fixture.path("valid").get(1).path("action").toString();
		assertThatIllegalArgumentException()
				.isThrownBy(() -> CanvasAgentPlan.validateAgainstContext(append, selected, 0, 2));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> CanvasAgentPlan.validateAgainstContext(append, selected, 1, 30));
	}

	@Test
	void actualGetResponsesMatchSharedWireWithoutFrontendReshaping() throws Exception {
		UUID storyboard = db.sql(
				"INSERT INTO video_storyboard(account_id,target_duration_seconds,request_payload) VALUES (:account,15,'{}') RETURNING id")
				.bind("account", ACCOUNT).map(row -> row.get(0, UUID.class)).one().block();
		UUID draft = db.sql(
				"INSERT INTO creation_draft(owner_account_id,title,source_type,status,version) VALUES (:account,'contract','independent','draft',1) RETURNING id")
				.bind("account", ACCOUNT).map(row -> row.get(0, UUID.class)).one().block();
		db.sql("INSERT INTO video_storyboard_workspace(storyboard_id,draft_id,account_id,operation_id,request_hash) VALUES (:sb,:draft,:account,gen_random_uuid(),'fixture')")
				.bind("sb", storyboard).bind("draft", draft).bind("account", ACCOUNT).then().block();
		for (JsonNode example : examples().path("valid")) {
			var action = (com.fasterxml.jackson.databind.node.ObjectNode) example.path("action").deepCopy();
			String kind = action.remove("kind").asText();
			String old = MAPPER.createObjectNode().set(kind, action).toString();
			UUID id = UUID.randomUUID();
			db.sql("INSERT INTO creation_canvas_agent_plan(id,account_id,operation_id,request_hash,draft_id,storyboard_id,base_draft_version,base_edit_version,"
					+ "base_canvas_revision,status,selected_node_ids,instruction,action,expires_at) VALUES (:id,:account,gen_random_uuid(),'fixture',:draft,:sb,1,1,1,'ready','[]','fixture',CAST(:action AS jsonb),now()+interval '30 minutes')")
					.bind("id", id).bind("account", ACCOUNT).bind("draft", draft).bind("sb", storyboard)
					.bind("action", old).then().block();
			String response = client().get().uri("/api/creation-assistant/canvas/plans/{id}", id)
					.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).exchange().expectStatus().isOk()
					.expectBody(String.class).returnResult().getResponseBody();
			assertThat(MAPPER.readTree(response).path("data").path("action")).isEqualTo(example.path("action"));
		}
	}

	@Test
	void malformedHttpInputsAreRejectedBeforeAnyPlanExists() {
		var base = new java.util.LinkedHashMap<String, Object>(
				Map.of("operationId", UUID.randomUUID().toString(), "draftId", UUID.randomUUID().toString(),
						"storyboardId", UUID.randomUUID().toString(), "selectedNodeIds", List.of(), "instruction", "修改",
						"expectedEditVersion", 1, "expectedCanvasRevision", 1));
		for (Map.Entry<String, Object> invalid : Map.<String, Object>of("instruction", "😀".repeat(2001),
				"selectedNodeIds", List.of("shot:valid", 3), "expectedEditVersion", 1.5, "provider", "untrusted")
				.entrySet()) {
			var request = new java.util.LinkedHashMap<>(base);
			request.put(invalid.getKey(), invalid.getValue());
			client().post().uri("/api/creation-assistant/canvas/plans")
					.header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
					.contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange().expectStatus()
					.isBadRequest();
		}
		assertThat(db.sql("SELECT count(*) FROM creation_canvas_agent_plan WHERE operation_id=CAST(:id AS uuid)")
				.bind("id", base.get("operationId")).map(row -> row.get(0, Long.class)).one().block()).isZero();
	}
}
