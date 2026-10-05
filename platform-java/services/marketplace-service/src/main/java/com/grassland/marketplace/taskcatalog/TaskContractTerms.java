package com.grassland.marketplace.taskcatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Canonical contract projection; field ownership lives in
 * contracts/task-contract.v1.json.
 */
public record TaskContractTerms(JsonNode values) {
	public TaskContractTerms {
		values = values.deepCopy();
	}

	@Override
	public JsonNode values() {
		return values.deepCopy();
	}
	private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
			.enable(DeserializationFeature.USE_LONG_FOR_INTS).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
	private static final JsonNode RULES = loadRules();

	private static JsonNode loadRules() {
		try (var stream = TaskContractTerms.class.getResourceAsStream("/contracts/task-contract.v1.json")) {
			if (stream == null)
				throw new IllegalStateException("Missing task contract rules");
			return JSON.readTree(stream);
		} catch (IOException error) {
			throw new IllegalStateException("Invalid task contract rules", error);
		}
	}

	public static boolean changed(Task before, Task after) {
		return !from(before).equals(from(after));
	}

	public static TaskContractTerms from(Task task) {
		JsonNode source = JSON.valueToTree(task);
		ObjectNode terms = JSON.createObjectNode();
		for (JsonNode field : RULES.path("fields")) {
			JsonNode value = source.at(field.path("source").asText());
			if (value.isMissingNode())
				throw new IllegalStateException("Unknown contract source: " + field);
			if (field.path("decodeJson").asBoolean() && !value.isNull()) {
				try {
					value = JSON.readTree(value.asText());
				} catch (IOException error) {
					throw new IllegalArgumentException("Invalid task cancellation policy", error);
				}
			}
			terms.set(field.path("key").asText(), value.isNull() ? field.get("nullValue") : value);
		}
		return new TaskContractTerms(terms);
	}

	/** Version is snapshot metadata, never a consent difference. */
	public static ObjectNode snapshot(Task task) {
		ObjectNode snapshot = (ObjectNode) from(task).values().deepCopy();
		snapshot.put("schemaVersion", RULES.path("schemaVersion").asInt());
		snapshot.put("taskVersion", task.version());
		return snapshot;
	}

	/**
	 * Plain values cross the HTTP boundary (Spring uses a different Jackson major
	 * version).
	 */
	public static Map<String, Object> snapshotBody(Task task) {
		return JSON.convertValue(snapshot(task), new TypeReference<Map<String, Object>>() {
		});
	}

	static JsonNode rules() {
		return RULES.deepCopy();
	}

	public static List<String> keys() {
		return RULES.path("fields").findValuesAsText("key");
	}
}
