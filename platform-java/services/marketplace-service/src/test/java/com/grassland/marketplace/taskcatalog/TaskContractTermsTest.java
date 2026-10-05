package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

class TaskContractTermsTest {
	private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	@Test
	void configuredNullDefaultsCompareLikeTheirExplicitNumericValue() {
		Task explicit = task(false, 7, null);
		ObjectNode legacy = JSON.valueToTree(explicit);
		legacy.retain(Arrays.stream(Task.class.getRecordComponents()).map(c -> c.getName()).toList());
		legacy.putNull("freebieDepositCents");
		Task nullable = JSON.convertValue(legacy, Task.class);
		assertThat(TaskContractTerms.changed(nullable, explicit)).isFalse();
		assertThat(TaskContractTerms.snapshot(nullable).get("freebieDepositCents").longValue()).isZero();
	}

	@Test
	void everyTaskAndRequirementFieldMustHaveAnExplicitClassification() {
		JsonNode rules = TaskContractTerms.rules();
		Set<String> contractSources = new HashSet<>();
		rules.path("fields").forEach(field -> contractSources.add(field.path("source").asText().split("/")[1]));
		Set<String> excluded = new HashSet<>();
		rules.path("nonContractTaskFields").fields().forEachRemaining(entry -> {
			assertThat(entry.getValue().asText()).isNotBlank();
			excluded.add(entry.getKey());
		});
		assertThat(contractSources).doesNotContainAnyElementsOf(excluded);
		contractSources.addAll(excluded);
		assertThat(contractSources).containsExactlyInAnyOrderElementsOf(
				Arrays.stream(Task.class.getRecordComponents()).map(c -> c.getName()).toList());
		assertThat(rules.path("requirementFields").findValuesAsText("key")).containsExactlyInAnyOrderElementsOf(
				Arrays.stream(TaskRequirements.class.getRecordComponents()).map(c -> c.getName()).toList());
	}

	@Test
	void changingEachRegisteredFieldChangesConsentAndSnapshot() {
		Task base = task(false, 7, "{\"script\":2000}");
		Map<String, Object> changes = Map.ofEntries(Map.entry("bountyCents", 1000),
				Map.entry("freebieDepositCents", 1500), Map.entry("platform", "douyin"),
				Map.entry("contentForm", "video"),
				Map.entry("requirements", Map.of("mustInclude", java.util.List.of("门店名称"))),
				Map.entry("questionText", "新的目标问题"), Map.entry("questionRef", "123"),
				Map.entry("commercePackageId", "00000000-0000-0000-0000-000000000001"),
				Map.entry("reviewRequired", true), Map.entry("deliveryDeadlineDays", 3),
				Map.entry("cancelPolicy", "{\"script\":3000}"));
		assertThat(changes.keySet()).containsExactlyInAnyOrderElementsOf(TaskContractTerms.keys());
		for (JsonNode field : TaskContractTerms.rules().path("fields")) {
			ObjectNode source = JSON.valueToTree(base);
			source.retain(Arrays.stream(Task.class.getRecordComponents()).map(c -> c.getName()).toList());
			String pointer = field.path("source").asText();
			int slash = pointer.lastIndexOf('/');
			ObjectNode parent = slash == 0 ? source : (ObjectNode) source.at(pointer.substring(0, slash));
			String key = field.path("key").asText();
			parent.set(pointer.substring(slash + 1), JSON.valueToTree(changes.get(key)));
			Task changed = JSON.convertValue(source, Task.class);
			assertThat(TaskContractTerms.changed(base, changed)).as(key).isTrue();
			assertThat(TaskContractTerms.snapshot(changed).get(key)).as(key)
					.isNotEqualTo(TaskContractTerms.snapshot(base).get(key));
		}
	}
	private Task task(Boolean review, Integer days, String cancellation) {
		return new Task("task", "owner", "org", "title", null, "published", "article", "xiaohongshu", null, 0L, null,
				null, 1, null, null, null, 1, null, TaskRequirements.empty(), null, 0L, null, null, null,
				TaskQuestion.none(), null, review, days, cancellation);
	}

	@Test
	void everyNewObligationChangesConsentButJsonOrderDoesNot() {
		Task base = task(false, 7, "{\"script\":2000,\"deliverable\":6000}");
		assertThat(TaskContractTerms.changed(base, task(true, 7, base.cancelPolicyJson()))).isTrue();
		assertThat(TaskContractTerms.changed(base, task(false, 1, base.cancelPolicyJson()))).isTrue();
		assertThat(TaskContractTerms.changed(base, task(false, 7, "{\"script\":1000}"))).isTrue();
		assertThat(TaskContractTerms.changed(base, task(null, 7, "{\"deliverable\":6000,\"script\":2000}"))).isFalse();
	}

	@Test
	void invalidDaysAreRejectedRatherThanBecomingImmediateDeadlines() {
		TaskContractFields.validateDeliveryDeadlineDays(null);
		TaskContractFields.validateDeliveryDeadlineDays(1);
		TaskContractFields.validateDeliveryDeadlineDays(Integer.MAX_VALUE);
		var policy = new EngagementDeliveryPolicy(7, 172800, 86400);
		for (int days : new int[]{0, -1, Integer.MIN_VALUE}) {
			assertThatIllegalArgumentException()
					.isThrownBy(() -> TaskContractFields.validateDeliveryDeadlineDays(days));
			assertThatIllegalArgumentException().isThrownBy(() -> policy.contractFor(task(false, days, null)));
		}
		assertThat(policy.contractFor(task(false, 1, null)).deliverySeconds()).isEqualTo(86400);
	}
}
