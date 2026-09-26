package com.grassland.edge.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Hypit 契约路由 ↔ Edge YAML 一致性（任务书 #107-1 C107-03 / K09.1 / TC107-03-02）。
 *
 * <p>
 * 验证：contracts/hypit-api.v1.json 每条 method+path 在 application.yml 有唯一
 * exact:true 条目且 upstream=intelligence；flag 命名一致；除 capabilities 外默认关闭；关闭态
 * 404（fail-closed）、 capabilities 可单独打开且不打开其它路由；YAML 中不存在契约外的 HYPIT 模板路由。
 */
@SpringBootTest(properties = {"management.server.port=0", "PUBLIC_BACKEND_ORIGIN=http://localhost:8080",
		"BILIBILI_PROXY_TOKEN_SECRET=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
		"DOUYIN_PROXY_TOKEN_SECRET=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
class HypitRoutesTest {

	@Autowired
	private EdgeRoutingProperties properties;

	private final ObjectMapper mapper = new ObjectMapper();

	private Path contractPath() {
		for (Path candidate : List.of(Path.of("..", "..", "..", "contracts", "hypit-api.v1.json"),
				Path.of("contracts", "hypit-api.v1.json"))) {
			if (Files.exists(candidate)) {
				return candidate;
			}
		}
		throw new IllegalStateException("contracts/hypit-api.v1.json not found from " + Path.of("").toAbsolutePath());
	}

	private List<RouteProperties> hypitRoutes() {
		return properties.routes().stream()
				.filter(route -> route.path() != null && route.path().startsWith("/api/hypit")).toList();
	}

	@Test
	void everyContractRouteHasOneExactYamlEntryWithMatchingFlag() throws Exception {
		JsonNode contract = mapper.readTree(Files.readString(contractPath()));
		List<RouteProperties> yaml = hypitRoutes();
		Set<String> yamlKeys = new HashSet<>();
		for (RouteProperties route : yaml) {
			assertThat(route.exact()).as("%s must be exact", route.path()).isTrue();
			assertThat(route.upstream()).as("%s upstream", route.path()).isEqualTo("intelligence");
			assertThat(yamlKeys.add(route.method() + " " + route.path()))
					.as("duplicate yaml route %s %s", route.method(), route.path()).isTrue();
		}
		List<String> problems = new ArrayList<>();
		for (JsonNode route : contract.get("routes")) {
			String key = route.get("method").asText() + " " + route.get("path").asText();
			RouteProperties match = yaml.stream()
					.filter(candidate -> candidate.method() != null
							&& candidate.method().equalsIgnoreCase(route.get("method").asText())
							&& candidate.path().equals(route.get("path").asText()))
					.findFirst().orElse(null);
			if (match == null) {
				problems.add("missing yaml entry: " + key);
				continue;
			}
			if (match.method() == null || !match.method().equalsIgnoreCase(route.get("method").asText())) {
				problems.add("method mismatch: " + key);
			}
		}
		assertThat(problems).as("contract↔yaml diff").isEmpty();
		assertThat(yaml.size()).as("yaml hypit route count").isEqualTo(contract.get("routes").size());
	}

	@Test
	void capabilitiesIsTheOnlyDefaultOpenRoute() {
		for (RouteProperties route : hypitRoutes()) {
			boolean capabilities = "/api/hypit/capabilities".equals(route.path());
			assertThat(route.enabled()).as("%s default must be %s", route.path(), capabilities ? "open" : "closed")
					.isEqualTo(capabilities);
		}
	}

	@Test
	void closedHypitRoutesFailClosedWhileCapabilitiesResolves() {
		UpstreamResolver resolver = new UpstreamResolver(properties);
		assertThat(resolver.resolveUpstreamName("GET", "/api/hypit/capabilities")).isEqualTo("intelligence");
		assertThat(resolver.resolveUpstreamName("GET", "/api/hypit/projects"))
				.isEqualTo(EdgeRoutingProperties.FAIL_CLOSED);
		assertThat(resolver.resolveUpstreamName("GET", "/api/hypit/projects/11111111-1111-4111-8111-111111111111"))
				.isEqualTo(EdgeRoutingProperties.FAIL_CLOSED);
		assertThat(resolver.resolveUpstreamName("POST", "/api/hypit/projects/p1/check"))
				.isEqualTo(EdgeRoutingProperties.FAIL_CLOSED);
	}
}
