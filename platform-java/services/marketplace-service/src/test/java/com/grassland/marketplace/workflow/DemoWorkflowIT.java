package com.grassland.marketplace.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 用 temporal-spring-boot-starter 内存 test-server 端到端验证 demo workflow
 * （HLD 532：Timer + Activity + WorkflowClient），不依赖 temporal 容器。
 *
 * <p>POST 启动 workflow（含 1s Timer sleep）→ GET 取结果（阻塞至完成）→ 断言两个 activity 串联输出。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "object-storage.enabled=false",
        "spring.temporal.test-server.enabled=true"
})
class DemoWorkflowIT {

    @LocalServerPort
    private int port;

    private WebTestClient webClient;

    @BeforeEach
    void setUp() {
        webClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void startThenResultRunsActivityAndTimer() {
        Map<String, Object> body = Map.of("seed", "hello-temporal", "sleepSeconds", 1);

        Map<String, String> started = webClient.post().uri("/workflow/demo")
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        assertThat(started).isNotNull();
        String workflowId = started.get("workflowId");
        assertThat(workflowId).startsWith("demo-");

        // GET 阻塞至 workflow 完成（含 Timer sleep），断言 prepare + finish 串联结果。
        webClient.get().uri("/workflow/demo/" + workflowId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.workflowId").isEqualTo(workflowId)
                .jsonPath("$.result").isEqualTo("prepared:hello-temporal|finished");
    }
}
