package com.grassland.identity.notify.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Prometheus registry、命名转换与独立 management port 暴露的集成验证（GL-P3-PLATFORM-001）。 */
class MailOutboxPrometheusEndpointIT extends IdentityItSupport {

	@LocalManagementPort
	private int managementPort;

	@Test
	void exposesMailDeadMetricsOnlyOnPrivateManagementPort() {
		String body = WebTestClient.bindToServer().baseUrl("http://localhost:" + managementPort)
				.responseTimeout(java.time.Duration.ofSeconds(30)).build().get().uri("/actuator/prometheus").exchange()
				.expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

		assertThat(body).contains("grassland_mail_outbox_dead_total").contains("grassland_mail_outbox_dead_current");

		client().get().uri("/actuator/prometheus").exchange().expectStatus().isNotFound();
	}
}
