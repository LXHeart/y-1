package com.grassland.intelligence.creationcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("FrozenAiConfigResolver")
class FrozenAiConfigResolverIT extends IntelligenceItSupport {
	private static final String ACCOUNT = "21212121-2121-2121-2121-212121212121";

	@Autowired
	FrozenAiConfigResolver resolver;

	@BeforeEach
	void clean() {
		db.sql("DELETE FROM ai_run").then().block();
		db.sql("DELETE FROM creation_context_snapshot").then().block();
		db.sql("DELETE FROM ai_provider_key").then().block();
		db.sql("DELETE FROM ai_provider_preference").then().block();
	}

	@Test
	@DisplayName("BYOK snapshot resolves the exact key version and fails closed after rotation")
	void byokVersionIsPinned() {
		// 任务书 #78 卡 B：个人段路由改读模型来源总开关——own 主行是 BYOK 命中的前提
		db.sql("INSERT INTO ai_provider_preference(account_id, capability, use_own_key) "
				+ "VALUES (:owner, '*', true)").bind("owner", ACCOUNT).then().block();
		String keyId = db.sql("""
				INSERT INTO ai_provider_key(
				    owner_account_id, capability, provider, base_url, model,
				    encrypted_key, key_version, masked_hint, enabled)
				VALUES (:owner,'text','openai-compatible','https://api.example.com',
				    'frozen-model','ciphertext-v7','v7','sk-***last',true)
				RETURNING id::text
				""").bind("owner", ACCOUNT).map(row -> row.get("id", String.class)).one().block();
		OffsetDateTime updatedAt = db.sql("SELECT updated_at FROM ai_provider_key WHERE id=CAST(:id AS uuid)")
				.bind("id", keyId).map(row -> row.get("updated_at", OffsetDateTime.class)).one().block();
		String snapshotId = db.sql("""
				INSERT INTO creation_context_snapshot(
				    account_id, task_id, application_id, task_version, platform_id, content_form_id,
				    task_snapshot, platform_rules_snapshot, material_snapshot, ai_config_snapshot)
				VALUES (:owner,:task,:application,1,'xiaohongshu','graphic','{}','{}','{}',
				    jsonb_build_object('resolutionType','BYOK','configId',:keyId,
				        'provider','openai-compatible','model','frozen-model',
				        'keyVersion','v7','configUpdatedAt',:updatedAt))
				RETURNING id::text
				""").bind("owner", ACCOUNT).bind("task", UUID.randomUUID().toString())
				.bind("application", UUID.randomUUID().toString()).bind("keyId", keyId)
				.bind("updatedAt", updatedAt.toInstant().toString()).map(row -> row.get("id", String.class)).one()
				.block();

		FrozenAiConfigResolver.ResolvedSnapshot resolved = resolver
				.resolve(UUID.fromString(snapshotId), ACCOUNT, "text").block();
		assertThat(resolved.provider().model()).isEqualTo("frozen-model");
		assertThat(resolved.provider().encryptedKey()).isEqualTo("ciphertext-v7");

		db.sql("UPDATE ai_provider_key SET encrypted_key='ciphertext-v8', key_version='v8', updated_at=now() "
				+ "WHERE id=CAST(:id AS uuid)").bind("id", keyId).then().block();

		assertThatThrownBy(() -> resolver.resolve(UUID.fromString(snapshotId), ACCOUNT, "text").block())
				.isInstanceOf(IntelligenceException.class).hasMessageContaining("BYOK 配置已变化或不可用");
	}
}
