package com.grassland.intelligence.ai.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * Hypit 外部执行准备（任务书 #107-1 C107-07 / K12.4：远程 Need 的 ai_run 留痕走 BYOK 零平台成本分支；凭据由
 * bridge 许可提供，绝不经 ExecutionContext）。
 *
 * <p>
 * 真 PG 落 run 行；本路径零积分 HTTP（BYOK 不扣平台费 D-11），断言集中在：run 冻结 provider=hypit
 * 与所选模型、无个人预算行（个人 BYOK 豁免）、上下文不含密文/明文 key、 以及 {@code prepareMediaExecution} 的
 * BYOK 零成本红线（K12.5）不被 Hypit 包装绕开。
 */
class HypitExternalPreparationTest extends IntelligenceItSupport {

	@Autowired
	AiExecutionService aiExecution;

	@Autowired
	DatabaseClient db;

	private final String account = UUID.randomUUID().toString();

	@BeforeEach
	void seed() {
		db.sql("DELETE FROM ai_run WHERE account_id = :account").bind("account", account).then()
				.then(db.sql("DELETE FROM ai_model_budget WHERE organization_id = :personal OR organization_id = :org")
						.bind("personal", "u:" + account).bind("org", "org-hypit-prep").then())
				.block(Duration.ofSeconds(10));
	}

	private long runCount(UUID operationId) {
		return db.sql("SELECT count(*) AS n FROM ai_run WHERE operation_id = CAST(:op AS uuid)")
				.bind("op", operationId.toString()).map((r, m) -> r.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
	}

	/** K12.4：个人域外部执行零平台成本留痕——run 冻结 hypit+所选模型，无 key、无积分、无个人预算行。 */
	@Test
	void tc107_07_prep_personalExternalRunRecordsZeroCostWithoutAnyKeyMaterial() {
		UUID operationId = UUID.randomUUID();
		var result = aiExecution.prepareHypitExternalExecution(account, null, "video.clone", "hypihub.default",
				"seedance-2-mini", operationId).block(Duration.ofSeconds(20));

		assertThat(result.allowed()).isTrue();
		assertThat(result.context().provider().provider()).isEqualTo("hypit");
		assertThat(result.context().provider().model()).as("不得按默认模型路由替换已选 Hypit 模型").isEqualTo("seedance-2-mini");
		assertThat(result.context().provider().baseUrl()).as("endpointId 仅作留痕标记").isEqualTo("hypihub.default");
		assertThat(result.context().provider().encryptedKey()).as("外置凭据绝不进入 ProviderResolution 密文字段").isNull();
		assertThat(result.context().decryptedKey()).as("Hypit 凭据走 bridge 许可，ExecutionContext 不带明文 key").isNull();
		assertThat(result.context().charge()).isNull();
		assertThat(result.context().creditFeature()).isNull();
		assertThat(result.context().priceTableVersion()).as("外部执行不冻结平台价表").isNull();

		assertThat(runCount(operationId)).isEqualTo(1);
		var run = db
				.sql("SELECT provider, model, run_type, budget_cents FROM ai_run"
						+ " WHERE operation_id = CAST(:op AS uuid)")
				.bind("op", operationId.toString())
				.map((r, m) -> java.util.Map.of("provider", String.valueOf(r.get("provider", String.class)), "model",
						String.valueOf(r.get("model", String.class)), "run_type",
						String.valueOf(r.get("run_type", String.class)), "budget_cents",
						String.valueOf(r.get("budget_cents", Integer.class))))
				.one().block(Duration.ofSeconds(10));
		assertThat(run.get("provider")).isEqualTo("hypit");
		assertThat(run.get("model")).isEqualTo("seedance-2-mini");
		assertThat(run.get("run_type")).isEqualTo("async");
		assertThat(run.get("budget_cents")).as("平台侧不预扣（成本按 bridge 回执结算）").isEqualTo("0");

		assertThat(db.sql("SELECT count(*) AS n FROM ai_model_budget WHERE organization_id = :scope")
				.bind("scope", "u:" + account).map((r, m) -> r.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10))).as("个人 BYOK 豁免：不建个人预算行").isZero();
	}

	/** 组织域外部执行按组织预算口径留痕（orgId 入 run 行与预算作用域，仍零积分）。 */
	@Test
	void tc107_07_prep_orgExternalRunScopedToOrganizationBudget() {
		UUID operationId = UUID.randomUUID();
		var result = aiExecution.prepareHypitExternalExecution(account, "org-hypit-prep", "video.clone",
				"pollo.default", "veo-3-fast", operationId).block(Duration.ofSeconds(20));
		assertThat(result.allowed()).isTrue();
		assertThat(result.context().organizationId()).isEqualTo("org-hypit-prep");
		assertThat(runCount(operationId)).isEqualTo(1);
		assertThat(db.sql("SELECT organization_id FROM ai_run WHERE operation_id = CAST(:op AS uuid)")
				.bind("op", operationId.toString()).map((r, m) -> r.get("organization_id", String.class)).one()
				.block(Duration.ofSeconds(10))).isEqualTo("org-hypit-prep");
	}

	/** K12.5 红线：Hypit 包装不得绕开 prepareMediaExecution 的 BYOK 零成本/无功能键约束。 */
	@Test
	void tc107_07_prep_byokZeroCostInvariantRejectsPricedOrFeaturedCall() {
		ProviderResolution hypit = ProviderResolution.byok("hypit", "hypihub.default", "seedance-2-mini", null, null);
		assertThatThrownBy(() -> aiExecution.prepareMediaExecution(account, null, "video.clone",
				com.grassland.intelligence.credits.CreditFeature.AI_RUN_TEXT, hypit, UUID.randomUUID(), 0, null, null)
				.block(Duration.ofSeconds(20))).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("零成本且不挂积分功能键");
		assertThatThrownBy(() -> aiExecution
				.prepareMediaExecution(account, null, "video.clone", null, hypit, UUID.randomUUID(), 5, null, null)
				.block(Duration.ofSeconds(20))).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("零成本且不挂积分功能键");
	}
}
