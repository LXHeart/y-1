package com.grassland.identity.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 任务书 #75 卡 C8：套餐推广分账佣金通知模板——仅真实佣金 &gt; 0 才产生通知（自然流量/自购零佣不打扰）， payload
 * 只带订单定位与金额（不泄露消费者账号）。
 */
class NotificationTemplatesSplitCommissionTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void realCommissionProducesWalletNotification() throws Exception {
		var payload = mapper.readTree(mapper.writeValueAsString(
				Map.of("orderRef", "order-1", "organizationId", "org-1", "recommenderAccountId", "rec-1",
						"recommenderAmountCents", 1000, "merchantAmountCents", 8500, "platformFeeCents", 500)));
		NotificationTemplates.Template template = NotificationTemplates.template("ConsumerPaymentSplitCompleted",
				payload);
		assertThat(template).isNotNull();
		assertThat(template.category()).isEqualTo(NotificationCategory.WALLET);
		assertThat(template.linkPath()).isEqualTo(NotificationTemplates.LINK_WALLET);
		assertThat(template.payload()).containsEntry("orderRef", "order-1")
				.containsEntry("recommenderAmountCents", 1000L).doesNotContainKey("recommenderAccountId");
	}

	@Test
	void zeroCommissionIsSilent() throws Exception {
		var zero = mapper.readTree(mapper
				.writeValueAsString(Map.of("orderRef", "order-2", "organizationId", "org-1", "recommenderAccountId",
						"rec-1", "recommenderAmountCents", 0, "merchantAmountCents", 9500, "platformFeeCents", 500)));
		assertThat(NotificationTemplates.template("ConsumerPaymentSplitCompleted", zero)).isNull();

		var missing = mapper.readTree(mapper.writeValueAsString(Map.of("orderRef", "order-3")));
		assertThat(NotificationTemplates.template("ConsumerPaymentSplitCompleted", missing)).isNull();
	}
}
